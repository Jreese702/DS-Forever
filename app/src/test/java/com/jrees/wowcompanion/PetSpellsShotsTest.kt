package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
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
 * #234 PET SPELLS (the user: "build it"; Spells s10, Book b63): the Spellbook room's Pet tab and the Bind box for a pet spell, drawn
 * by Robolectric's native graphics from the documents the add-on's Lua REALLY sends (tools/test_pet_spells.py writes them to
 * dsforever/src/test/resources/pet-fixtures). The touch checks always run; the PNGs only when PET_SHOTS_DIR is set (PET_ART_ROOT = an
 * art root laid out like the app's: ui/kit, fonts, icons.pak).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PetSpellsShotsTest {
    private val out: File? = System.getenv("PET_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()

    private fun artRoot() {
        val root = System.getenv("PET_ART_ROOT") ?: return
        val f = ArtHarvest::class.java.getDeclaredField("rootDir")
        f.isAccessible = true
        f.set(null, File(root))
    }

    private fun fx(name: String): JSONObject {
        val dirs = listOf(File("../dsforever/src/test/resources/pet-fixtures"), File("dsforever/src/test/resources/pet-fixtures"))
        val f = dirs.map { File(it, "$name.json") }.firstOrNull { it.isFile } ?: error("fixture $name (run tools/test_pet_spells.py)")
        return JSONObject(f.readText())
    }

    private fun book(name: String) = SpellsModel.parse(fx(name))!!
    private fun ctl(name: String, edit: (JSONObject) -> Unit = {}): SpellsModel.Ctl {
        val d = fx(name)
        edit(d.getJSONObject("ctl"))
        return SpellsModel.parseCtl(d)!!
    }

    private fun lay(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper() }
    private fun draw(v: View, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) { val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c); if (pass == 0) settle() }
        return b
    }
    private fun save(b: Bitmap, name: String) {
        val d = out ?: return
        d.mkdirs()
        File(d, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Room(val v: SpellbookView, val binds: ArrayList<SpellsModel.Entry>)
    private fun room(b: SpellsModel.Book, c: SpellsModel.Ctl?): Room {
        val binds = ArrayList<SpellsModel.Entry>()
        val v = SpellbookView(app, onBind = { binds += it })
        lay(v, 1240, 960)
        v.update(b)
        if (c != null) v.onCtl(c)
        return Room(v, binds)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = android.os.SystemClock.uptimeMillis()
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0))
    }

    /** the Bind button's middle (SpellbookView.bindRect: SPLIT + 40 .. w - 56, h - 141 .. h - 30) */
    private val bindAt = (740f + 40f + 1240f - 56f) / 2f to 960f - 85f

    @Test fun aFingerBindsAPetSpellAndNeverACommand() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val b = book("spells_hunter_bound")
        // the controller's glow on Growl in the Pet tab (line 101): the room shows the Pet tab with Growl chosen
        val r = room(b, ctl("spellsctl_pet_book"))
        draw(r.v, 1240, 960)
        tap(r.v, bindAt.first, bindAt.second)
        assertEquals(listOf("Growl"), r.binds.map { it.name })
        // a command chosen: no Bind
        val a = room(b, ctl("spellsctl_pet_book") { it.put("sel", b.petItems.first { e -> e.name == "Attack" }.actionId); it.put("idx", 1) })
        draw(a.v, 1240, 960)
        tap(a.v, bindAt.first, bindAt.second)
        assertTrue("Attack is a command: no Bind", a.binds.isEmpty())
        // review (Spells s10b): Seduction cannot auto-cast (the pet bar's state 1) - still a spell a finger binds; Soothing Aura is passive
        val succ = book("spells_succubus")
        val s = room(succ, ctl("spellsctl_pet_book") { it.put("sel", 6358); it.put("idx", 2) })
        draw(s.v, 1240, 960)
        tap(s.v, bindAt.first, bindAt.second)
        assertEquals(listOf("Seduction"), s.binds.map { it.name })
        val p = room(succ, ctl("spellsctl_pet_book") { it.put("sel", 20000); it.put("idx", 4) })
        draw(p.v, 1240, 960)
        tap(p.v, bindAt.first, bindAt.second)
        assertTrue("Soothing Aura is passive: no Bind", p.binds.isEmpty())
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        val hunter = book("spells_hunter_bound")
        // the Pet tab, the glow on Growl (bound on ZR + A, auto-cast on)
        save(draw(room(hunter, ctl("spellsctl_pet_book")).v, 1240, 960), "pet_tab_growl_bound.png")
        // Bite: on no bar (the game's gold glow, "Not on a button"), Bind offered
        save(draw(room(hunter, ctl("spellsctl_pet_book") { it.put("sel", 17253); it.put("idx", 5) }).v, 1240, 960), "pet_tab_bite_unbound.png")
        // Attack: a command (no Bind, no button line)
        save(draw(room(hunter, ctl("spellsctl_pet_book") { it.put("sel", hunter.petItems.first { e -> e.name == "Attack" }.actionId); it.put("idx", 1) }).v,
            1240, 960), "pet_tab_attack_command.png")
        // the cursor on the tab row: the Pet tab's page (how many spells, how many not on a button)
        save(draw(room(hunter, ctl("spellsctl_pet_book") { it.put("tabs", true) }).v, 1240, 960), "pet_tab_row.png")
        // a Warlock's imp, Firebolt chosen
        val lock = book("spells_warlock")
        save(draw(room(lock, ctl("spellsctl_pet_book") { it.put("sel", 3110); it.put("idx", 3) }).v, 1240, 960), "pet_tab_warlock_firebolt.png")
        // review (Spells s10b): a succubus, Seduction chosen - a spell that cannot auto-cast is still an active spell with Bind
        val succ = book("spells_succubus")
        save(draw(room(succ, ctl("spellsctl_pet_book") { it.put("sel", 6358); it.put("idx", 2) }).v, 1240, 960), "pet_tab_succubus_seduction.png")
        // the Shaman (no pet): the book as before, no Pet tab
        save(draw(room(book("spells_shaman"), null).v, 1240, 960), "shaman_no_pet_tab.png")
        // the Controller room's chooser on the Spells tab, the glow on Growl: the class's spells, then the pet's (tools/test_pet_spells.py
        // with PET_DUMP=<PET_SHOTS_DIR> writes docs/: a render aid, never committed)
        val docs = File(out, "docs")
        if (File(docs, "padmap_pet.json").isFile) {
            val v = ControllerView(app, { true })
            v.update(SpellsModel.parse(JSONObject(File(docs, "spells_croom.json").readText()))!!)
            v.onPadMap(PadMapModel.parse(JSONObject(File(docs, "padmap_pet.json").readText()))!!)
            v.onPadTabCtl(PadMapModel.parseCtl(JSONObject(File(docs, "padtabctl_pet_chooser.json").readText()))!!)
            lay(v, 1240, 960)
            save(draw(v, 1240, 960), "controller_chooser_pet_spells.png")
        }
        // the Bind box for Growl (Normal), and its In combat tab
        for ((name, fight) in listOf("pet_bind_box_normal.png" to false, "pet_bind_box_in_combat.png" to true)) {
            val o = SpellsOverlay(app, otherReader = { true })
            o.onBook(hunter)
            o.onCtl(ctl("spellsctl_pet_bind") { if (fight) it.put("bf", true) })
            lay(o, 1240, 960)
            save(draw(o, 1240, 960), name)
        }
    }
}
