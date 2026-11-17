package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * bags-gear1 THE EQUIP SLIDE-OUT, drawn off-device (the user, 2026-11-16: "Choose which ring or weapon slot to replace"; "Design: X opens a
 * slide-out of ARMOR (item icons) next to the item. It lists the options for that slot, plus a blank entry that unequips."; "The Y text
 * menu stays for enchant and bind (use-effect items)."; "When pressed, X animates the options sliding out."): the Character room at
 * 1240 x 1007 from a made-up character - one rail per doll column (the left column slides right, the right column left, the weapons
 * up), the blank's Unequip card, a fight's greyed rail, a long rail's chevron, the ammo slot's rail, the slide half way, the middle card's
 * Swap / Enchant buttons and the Y menu (Enchant + Bind) - and the Bags room's two-target row for a ring. The rail's card stands in the
 * right pane: the game's full tooltip where a Bags visit kept one, else the short card.
 * Runs only when EQUIP_SHOTS_DIR is set; EQUIP_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EquipSlideOutShotsTest {
    private val out: File? = System.getenv("EQUIP_SHOTS_DIR")?.let { File(it) }

    private fun artRoot() {
        val root = System.getenv("EQUIP_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun l(t: String, c: String = "ffffff") = JSONObject().put("t", t).put("c", c)
    private fun arr(vararg o: JSONObject) = JSONArray().also { a -> o.forEach { a.put(it) } }
    private fun stat(k: String, n: String, v: Int) = JSONObject().put("k", k).put("n", n).put("v", v)
    private val sta = "ITEM_MOD_STAMINA_SHORT"; private val str = "ITEM_MOD_STRENGTH_SHORT"; private val agi = "ITEM_MOD_AGILITY_SHORT"
    private val spi = "ITEM_MOD_SPIRIT_SHORT"; private val arm = "RESISTANCE0_NAME"

    /** a bags row */
    private fun item(id: Int, name: String, equip: String, q: Int, ilvl: Int, icon: Int, stats: JSONArray, count: Int = 1) =
        JSONObject().put("id", id).put("name", name).put("equip", equip).put("quality", q).put("ilvl", ilvl).put("icon", icon)
            .put("count", count).put("stats", stats)

    /** a worn row (bags.equipped.slots) */
    private fun worn(slot: Int, name: String, id: Int? = null, item: String? = null, icon: Int = 0, q: Int = 1, equip: String? = null,
                     stats: JSONArray? = null, tip: JSONArray? = null, use: String? = null) =
        JSONObject().put("slot", slot).put("name", name).also { o ->
            if (id != null) { o.put("id", id).put("icon", icon).put("quality", q).put("dur", 100) }
            if (item != null) o.put("item", item)
            if (equip != null) o.put("equip", equip)
            if (stats != null) o.put("stats", stats)
            if (tip != null) o.put("tip", tip)
            if (use != null) o.put("use", true).put("useName", use)
        }

    // the bags: (bag, slot, row)
    private val bagItems = listOf(
        Triple(0, 1, item(3101, "Stonemason Cowl", "INVTYPE_HEAD", 2, 18, 133071, arr(stat(arm, "Armor", 41), stat(sta, "Stamina", 4), stat(str, "Strength", 3)))),
        Triple(0, 2, item(3102, "Ragged Leather Cap", "INVTYPE_HEAD", 1, 9, 133117, arr(stat(arm, "Armor", 12)))),
        Triple(0, 3, item(3201, "Ring of the Copper Vein", "INVTYPE_FINGER", 2, 15, 133344, arr(stat(sta, "Stamina", 3), stat(agi, "Agility", 2)))),
        Triple(0, 4, item(3202, "Seal of Wrynn", "INVTYPE_FINGER", 3, 22, 133345, arr(stat(str, "Strength", 5), stat(sta, "Stamina", 4)))),
        Triple(0, 5, item(3203, "Tarnished Band", "INVTYPE_FINGER", 1, 5, 133346, JSONArray())),
        Triple(0, 6, item(3301, "Heavy Ironwood Maul", "INVTYPE_2HWEAPON", 3, 20, 133052, arr(stat(str, "Strength", 8), stat(sta, "Stamina", 5)))),
        Triple(0, 7, item(3302, "Militia Hammer", "INVTYPE_WEAPON", 1, 8, 133476, JSONArray())),
        Triple(0, 8, item(3401, "Stonemason Chestguard", "INVTYPE_CHEST", 2, 18, 132637, arr(stat(arm, "Armor", 58), stat(sta, "Stamina", 4)))),
        Triple(0, 9, item(2512, "Rough Arrow", "INVTYPE_AMMO", 1, 5, 132382, JSONArray(), count = 200)),
        Triple(0, 10, item(2515, "Sharp Arrow", "INVTYPE_AMMO", 1, 15, 132382, JSONArray(), count = 141)),
        Triple(1, 1, item(3204, "Band of Thorns", "INVTYPE_FINGER", 2, 12, 133347, arr(stat(sta, "Stamina", 2)))),
        Triple(1, 2, item(3205, "Glinting Ring", "INVTYPE_FINGER", 2, 14, 133348, arr(stat(spi, "Spirit", 3)))),
        Triple(1, 3, item(3206, "Copper Ring", "INVTYPE_FINGER", 1, 4, 133349, JSONArray())),
        Triple(1, 4, item(5001, "Light Armor Kit", "", 1, 5, 133611, JSONArray()).put("kit", true).put("use", true)),
    )

    /** the full tooltip a Bags visit kept, for the Seal (with the game's own changes for both fingers) */
    private val sealTip = JSONObject().put("key", "bag 0 4 3202")
        .put("lines", arr(l("Seal of Wrynn", "0070dd"), l("Binds when equipped"), l("Unique"), l("Finger"), l("+5 Strength"), l("+4 Stamina"),
            l("Requires Level 15")))
        .put("cmp", arr(
            JSONObject().put("slot", 11).put("dn", 3).put("nw", true).put("lines", arr(l(" "), l("If you replace this item, the following stat changes will occur:", "ffd100"),
                l("+5 Strength", "00ff00"), l("+2 Stamina", "00ff00"), l("-1 Spirit", "ff2020"))),
            JSONObject().put("slot", 12).put("dn", 2).put("nw", true).put("lines", arr(l(" "), l("If you replace this item, the following stat changes will occur:", "ffd100"),
                l("+5 Strength", "00ff00"), l("+4 Stamina", "00ff00")))))

    private fun equipped(ammo: Boolean = false): JSONObject {
        val slots = JSONArray()
            .put(worn(1, "Head", 900, "Tattered Hood", 133117, 1, "INVTYPE_HEAD", arr(stat(arm, "Armor", 8))))
            .put(worn(2, "Neck"))
            .put(worn(3, "Shoulder", 901, "Rough Pauldrons", 135036, 1, "INVTYPE_SHOULDER", arr(stat(arm, "Armor", 14))))
            .put(worn(4, "Shirt", 902, "Recruit's Shirt", 135022, 1, "INVTYPE_BODY"))
            .put(worn(5, "Chest", 903, "Tattered Cloth Vest", 135009, 1, "INVTYPE_CHEST", arr(stat(arm, "Armor", 12), stat(sta, "Stamina", 1)),
                arr(l("Tattered Cloth Vest"), l("Soulbound"), l("Chest"), l("12 Armor"), l("+1 Stamina"))))
            .put(worn(6, "Waist", 904, "Frayed Belt", 132491, 1, "INVTYPE_WAIST", arr(stat(arm, "Armor", 4))))
            .put(worn(7, "Legs", 905, "Rough Leggings", 134582, 1, "INVTYPE_LEGS", arr(stat(arm, "Armor", 9))))
            .put(worn(8, "Feet", 906, "Worn Boots", 132535, 1, "INVTYPE_FEET", arr(stat(arm, "Armor", 6))))
            .put(worn(9, "Wrist"))
            .put(worn(10, "Hands", 907, "Ragged Gloves", 132939, 1, "INVTYPE_HAND", arr(stat(arm, "Armor", 3))))
            .put(worn(11, "Finger 1", 908, "Band of Thorns", 133347, 2, "INVTYPE_FINGER", arr(stat(sta, "Stamina", 2), stat(spi, "Spirit", 1))))
            .put(worn(12, "Finger 2"))
            .put(worn(13, "Trinket 1", 909, "Insignia of the Alliance", 133452, 3, "INVTYPE_TRINKET", JSONArray(),
                arr(l("Insignia of the Alliance", "0070dd"), l("Soulbound"), l("Trinket"), l("Use: Removes all movement impairing effects.", "00ff00")),
                use = "PvP Trinket"))
            .put(worn(14, "Trinket 2"))
            .put(worn(15, "Back", 910, "Frayed Cloak", 133762, 1, "INVTYPE_CLOAK", arr(stat(arm, "Armor", 5))))
            .put(worn(16, "Main Hand", 911, "Worn Shortsword", 135274, 1, "INVTYPE_WEAPON"))
            .put(worn(17, "Off Hand", 912, "Battered Buckler", 134955, 1, "INVTYPE_SHIELD", arr(stat(arm, "Armor", 57))))
            .put(worn(18, "Ranged", 913, "Short Bow", 135489, 1, "INVTYPE_RANGED"))
            .put(worn(19, "Tabard"))
        return JSONObject().put("slots", slots).put("rangedShown", true).also { e ->
            if (ammo) e.put("ammoNeeded", true).put("ammo", JSONObject().put("id", 3030).put("icon", 132382).put("quality", 1).put("count", 512).put("item", "Razor Arrow"))
        }
    }

    private fun bagsDoc(free: Int, ammo: Boolean = false) = JSONObject().put("freeGeneral", free).put("equipped", equipped(ammo))
        .put("pad", JSONObject().put("rows", JSONObject().put("bag0", 30).put("bag1", 31).put("bagsort", 6).put("bmvq", 7).put("bmv", 55).put("auracancel", 29))
            .put("sizes", JSONObject().put("bagsort", 40)))

    private fun charDoc(combat: Boolean = false) = JSONObject().put("type", "character").put("combat", combat).put("c", JSONObject()
        .put("eqs", JSONObject().put("row", "auracancel").put("yes", 3).put("no", 4).put("uq", 5).put("mon", 6).put("moff", 7).put("msb", 8)
            .put("wb", 9).put("ba", 10).put("bb", 11).put("ask", false)))

    private fun call(v: CharacterView, name: String, slot: Int) {
        val m = CharacterView::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
        m.isAccessible = true
        m.invoke(v, slot)
    }
    private fun set(v: CharacterView, name: String, value: Any?) {
        val f = CharacterView::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(v, value)
    }

    private fun room(free: Int = 3, combat: Boolean = false, ammo: Boolean = false, items: List<Triple<Int, Int, JSONObject>> = bagItems): CharacterView {
        val v = CharacterView(RuntimeEnvironment.getApplication())
        v.bagItems = { items }
        v.kits = { items.filter { it.third.optBoolean("kit") } }
        v.bagTip = { b, s -> if (b == 0 && s == 4) sealTip else null }
        v.updateBags(bagsDoc(free, ammo))
        v.update(charDoc(combat))
        return v
    }

    private fun draw(name: String, v: View, settleMs: Long = 400L) {
        val w = 1240; val h = 1007
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..3) {
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val c = Canvas(b)
            c.drawColor(Theme.ink)
            v.draw(c)
            if (pass < 3) ShadowSystemClock.advanceBy(Duration.ofMillis(settleMs))
            ShadowLooper.idleMainLooper()
            Thread.sleep(120)                                                 // icons decode off the UI thread
            ShadowLooper.idleMainLooper()
        }
        val dir = out ?: return
        dir.mkdirs()
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** the rail for [slot], [sel] highlighted, drawn once it has slid out */
    private fun rail(name: String, slot: Int, sel: Int = 0, v: CharacterView = room()) {
        set(v, "chosenSlot", slot)
        call(v, "openRail", slot)
        set(v, "railSel", sel)
        draw(name, v)
    }

    @Test fun shots() {
        artRoot()
        Tr.init(RuntimeEnvironment.getApplication())
        // one rail per column (D3a): the left column slides right, the right column left, the weapons up
        rail("rail_left_head.png", 1, 0)
        rail("rail_right_ring_fulltip.png", 11, 0)               // the Seal: its full tooltip (a Bags visit kept it), Finger 1's changes
        rail("rail_right_ring_short.png", 11, 1)                 // the Copper Vein ring: the short card
        rail("rail_weapons_main_bagsfull.png", 16, 0, room(free = 0))   // the maul over a worn buckler, bags full: greyed
        rail("rail_blank_chest.png", 5, 1)                       // the blank: Unequip and the losses
        rail("rail_fight_chest.png", 5, 0, room(combat = true))  // a fight: armour greyed, A says why
        rail("rail_long_ring_chevron.png", 11, 4)                // six rings and the blank: four shown, the chevrons
        rail("rail_ammo.png", 0, 0, room(ammo = true))           // the ammo slot: the bags' arrows, no blank
        // the slide half way (D5a: 160 ms, 15 ms apart)
        room().let { v ->
            set(v, "chosenSlot", 11); call(v, "openRail", 11)
            val at = CharacterView::class.java.getDeclaredField("railAt").also { it.isAccessible = true }
            at.setLong(v, android.os.SystemClock.uptimeMillis() - 70L)
            val w = 1240; val h = 1007
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            Canvas(b).also { c -> c.drawColor(Theme.ink); v.draw(c) }
            out?.let { d -> d.mkdirs(); File(d, "rail_ring_sliding_70ms.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        // the middle card's buttons (no rail): Swap + Enchant on a plain piece, Swap + More on the trinket, Equip on an empty slot
        room().let { v -> set(v, "chosenSlot", 5); draw("card_chest_swap_enchant.png", v) }
        room().let { v -> set(v, "chosenSlot", 13); draw("card_trinket_swap_more.png", v) }
        room().let { v -> set(v, "chosenSlot", 12); draw("card_empty_finger_equip.png", v) }
        // Y on the trinket (a use effect): the menu, Enchant + Bind to a Button
        room().let { v -> set(v, "chosenSlot", 13); call(v, "openY", 13); draw("ymenu_trinket_enchant_bind.png", v) }
        bags()
    }

    // ---------------------------------------------------------------- the Bags room's two-target row (D1a: a ring)

    private fun bagsShot(name: String, mode: String, at: Int, ringSlot: Int = 6, fight: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        val v = BagsView(app)
        val ring = item(3201, "Ring of the Copper Vein", "INVTYPE_FINGER", 2, 15, 133344, arr(stat(sta, "Stamina", 3), stat(agi, "Agility", 2)))
            .put("bag", 0).put("slot", ringSlot)
        val items = JSONArray()
            .put(item(6948, "Hearthstone", "", 1, 1, 134414, JSONArray()).put("bag", 0).put("slot", 1).put("use", true))
            .put(item(3401, "Stonemason Chestguard", "INVTYPE_CHEST", 2, 18, 132637, arr(stat(arm, "Armor", 58))).put("bag", 0).put("slot", 5))
            .put(ring)
        val tip = JSONObject().put("key", "bag 0 $ringSlot 3201")
            .put("lines", arr(l("Ring of the Copper Vein", "1eff00"), l("Binds when equipped"), l("Finger"), l("+2 Agility"), l("+3 Stamina"), l("Requires Level 12")))
            .put("cmp", arr(
                JSONObject().put("slot", 11).put("dn", 3).put("nw", true).put("lines", arr(l(" "), l("If you replace this item, the following stat changes will occur:", "ffd100"),
                    l("+2 Agility", "00ff00"), l("+1 Stamina", "00ff00"), l("-1 Spirit", "ff2020"))),
                JSONObject().put("slot", 12).put("dn", 2).put("nw", true).put("lines", arr(l(" "), l("If you replace this item, the following stat changes will occur:", "ffd100"),
                    l("+2 Agility", "00ff00"), l("+3 Stamina", "00ff00")))))
        val targets = JSONArray()
            .put(JSONObject().put("inv", 11).put("id", 908).put("icon", 133347).put("quality", 2).put("name", "Band of Thorns").put("ok", !fight)
                .also { if (fight) it.put("why", "combat") })
            .put(JSONObject().put("inv", 12).put("ok", !fight).also { if (fight) it.put("why", "combat") })
        val eq = JSONObject().put("gear", true).put("bag", 0).put("slot", ringSlot).put("id", 3201).put("at", at)
        if (mode == "equip") eq.put("targets", targets) else eq.put("to", 10 + at)
        val ctl = JSONObject().put("on", true).put("mode", mode).put("seq", 1).put("sid", 1)
            .put("cur", JSONObject().put("bag", 0).put("slot", ringSlot)).put("tip", tip).put("eq", eq)
        val doc = JSONObject().put("free", 12).put("total", 16).put("money", 102345).put("combat", fight)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)))
            .put("items", items).put("equipped", equipped()).put("ctl", ctl)
            .put("pad", JSONObject().put("rows", JSONObject().put("bmvq", 27).put("bag0", 30)))
        v.update(JSONObject(doc.toString()).put("ctl", JSONObject().put("on", false)), true)   // the baseline (no replayed requests)
        v.update(doc, true)
        // in a window, so the layer finds the grid where it is (getLocationOnScreen of detached views is 0, 0: the row would sit up top)
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        act.setContentView(v, android.view.ViewGroup.LayoutParams(1240, 1007))
        draw(name, v)
    }

    private fun bags() {
        bagsShot("bags_ring_row_empty_finger.png", "equip", 2)       // the start: the EMPTY Finger 2, its all-gains
        bagsShot("bags_ring_row_finger1.png", "equip", 1)            // Finger 1: only its changes (-1 Spirit)
        bagsShot("bags_ring_row_bindask.png", "bindask", 2)          // the game's own "bind it to you?"
        // review: the ring in the grid's last column: the row on the tile's LEFT still reads Finger 1, Finger 2 left to right (the D-pad's
        // right = the next slot); and a fight begun while the row is open: the slots faint, the reason under the changes
        bagsShot("bags_ring_row_left_edge.png", "equip", 1, ringSlot = 10)
        bagsShot("bags_ring_row_fight.png", "equip", 2, fight = true)
    }

    // ---------------------------------------------------------------- review: a finger on the card's A / B, and the ammo rail's place

    private fun get(v: CharacterView, name: String): Any? =
        CharacterView::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(v)

    /** one frame drawn (no file), the slide-out finished */
    private fun frame(v: CharacterView) {
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

    private fun tap(v: CharacterView, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        v.onTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        v.onTouchEvent(android.view.MotionEvent.obtain(t, t + 50, android.view.MotionEvent.ACTION_UP, x, y, 0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun cardHit(v: CharacterView, which: String): android.graphics.RectF =
        (get(v, "railCardHits") as List<Pair<android.graphics.RectF, String>>).first { it.second == which }.first

    private fun chosenOfTheWayBack(v: CharacterView): Int? = get(v, "railBack")?.let { b ->
        b.javaClass.getDeclaredField("chosen").also { it.isAccessible = true }.getInt(b)
    }

    /** review: the card's "A Equip" by a finger is A on the highlighted icon (it closed the rail as B before); its "B Back" is B */
    @Test fun aFingerOnTheCardsAIsATheBIsB() {
        Tr.init(RuntimeEnvironment.getApplication())
        val a = room()
        set(a, "chosenSlot", 1); call(a, "openRail", 1); frame(a)
        val ra = cardHit(a, "a")
        tap(a, ra.centerX(), ra.centerY())
        assertEquals("A on the card: the rail closed", null, get(a, "railFor"))
        assertEquals("...with the highlighted piece flying into the slot (A, not B)", 0, chosenOfTheWayBack(a))
        assertEquals("...and its route tried (no game in a test: not taken)", "The game did not take it", get(a, "kitLine"))

        val b = room()
        set(b, "chosenSlot", 1); call(b, "openRail", 1); frame(b)
        val rb = cardHit(b, "b")
        tap(b, rb.centerX(), rb.centerY())
        assertEquals("B on the card: the rail closed", null, get(b, "railFor"))
        assertEquals("...nothing chosen", -1, chosenOfTheWayBack(b))
        assertEquals("...nothing tried", null, get(b, "kitLine"))

        val c = room()
        set(c, "chosenSlot", 1); call(c, "openRail", 1); frame(c)
        val rc = cardHit(c, "a")
        tap(c, rc.centerX(), rc.top - 200f)                       // the card's middle: not a button, the rail goes back in (B)
        assertEquals(null, get(c, "railFor"))
        assertEquals(-1, chosenOfTheWayBack(c))
    }

    /** review: the ammo slot's rail starts above the weapons row (its first icon covered the ranged slot's corner) */
    @Test fun theAmmoRailStandsClearOfTheWeaponsRow() {
        Tr.init(RuntimeEnvironment.getApplication())
        val v = room(ammo = true)
        set(v, "chosenSlot", 0); call(v, "openRail", 0); frame(v)
        @Suppress("UNCHECKED_CAST")
        val icons = (get(v, "railHits") as List<Pair<android.graphics.RectF, Int>>).map { it.first }
        assertEquals("the two kinds of arrows in the bags (the worn kind left out)", 2, icons.size)
        val boxes = (get(v, "focusables") as List<*>).associate { f ->
            val k = f!!.javaClass.getDeclaredField("key").also { it.isAccessible = true }.get(f) as String
            k to (f.javaClass.getDeclaredField("r").also { it.isAccessible = true }.get(f) as android.graphics.RectF)
        }
        val weaponsTop = listOf(16, 17, 18).mapNotNull { boxes["slot:$it"]?.top }.minOrNull()
        val rightColumn = listOf(10, 6, 7, 8, 11, 12, 13, 14).mapNotNull { boxes["slot:$it"]?.left }.minOrNull()
        org.junit.Assert.assertNotNull(weaponsTop); org.junit.Assert.assertNotNull(rightColumn)
        for (r in icons) {
            org.junit.Assert.assertTrue("an ammo icon $r above the weapons row (top $weaponsTop)", r.bottom <= weaponsTop!!)
            org.junit.Assert.assertTrue("an ammo icon $r clear of the right column ($rightColumn)", r.right <= rightColumn!!)
        }
    }

    /** bags-gear2 (the user, 2026-11-16: "the tool tip is in front of the options"): the Character room's rail never reaches the card's
     *  pane - every slot's rail, a dual wielder's hands (the other hand's one-hander listed) and a class that can't, every entry chosen */
    @Test fun theRailNeverReachesTheCardsPane() {
        Tr.init(RuntimeEnvironment.getApplication())
        for (dw in listOf(false, true)) for (slot in Doll.SLOTS.map { it.first } + 0) {
            val v = room(ammo = true)
            if (dw) v.updateBags(bagsDoc(3, ammo = true).also { it.getJSONObject("equipped").put("dw", true) })
            set(v, "chosenSlot", slot); call(v, "openRail", slot)
            if (get(v, "railFor") == null) continue                               // nothing fits that slot: no rail
            for (sel in 0 until 6) {
                set(v, "railSel", sel); frame(v)
                @Suppress("UNCHECKED_CAST")
                val icons = (get(v, "railHits") as List<Pair<android.graphics.RectF, Int>>).map { it.first }
                @Suppress("UNCHECKED_CAST")
                val card = (get(v, "railCardHits") as List<Pair<android.graphics.RectF, String>>).map { it.first }
                if (card.isEmpty()) continue
                val cardLeft = 704f + 26f                                         // the right pane (split + its inset): the card's left
                for (r in icons) org.junit.Assert.assertTrue("dw=$dw slot $slot sel $sel: rail icon $r clear of the card's pane",
                    r.right <= 704f && card.none { c -> android.graphics.RectF.intersects(c, r) } && r.right < cardLeft)
            }
        }
    }

    /** the doll's columns (Doll.SLOTS) and SlideOut's ways out agree */
    @Test fun theRailsWayFollowsTheDollsColumns() {
        for ((slot, pos) in Doll.SLOTS) {
            val want = when (pos.first) { 0 -> SlideOut.Dir.RIGHT; 1 -> SlideOut.Dir.LEFT; else -> SlideOut.Dir.UP }
            assertEquals("slot $slot", want, SlideOut.dir(slot))
        }
    }
}
