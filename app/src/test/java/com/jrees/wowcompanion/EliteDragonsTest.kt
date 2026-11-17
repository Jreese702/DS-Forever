package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * THE CLASSIFICATION DRAGONS ON THE BOTTOM SCREEN (the user, 2026-11-17: "the Skull above mobs is confusing, I think we need to scrap
 * that, we do however need to add the dragon Icon for an Elite and a silver one for rare spawns"). The add-on (Trail.lua ag14, Hub.lua
 * uf25) draws the game's plate dragons on the top screen; the Settings room's Top screen tiles picture those elements, so the Target bar
 * and Enemy nameplates pictures carry them too, from the kit (`elite_gold` / `elite_silver`, kit.json members, KitSteps.Kit v5).
 *
 * With ELITE_SHOTS_DIR set it renders the two pictures at the band's size (438 x 220, x2) with the art root ELITE_ART_ROOT (an art root
 * laid out like the app's: ui/kit, fonts) and with none (the drawn stand-in).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EliteDragonsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun art(root: File?) {
        val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, root)
        UiKit.root = UiKit.root                                         // the kit's caches forget
    }

    @After fun noArt() = art(null)

    @Test fun theRuleIsTheGames() {
        for (c in listOf("elite", "worldboss", "e", "b")) assertEquals(c, TilePictures.Dragon.GOLD, TilePictures.dragonOf(c))
        for (c in listOf("rare", "rareelite", "r", "R")) assertEquals(c, TilePictures.Dragon.SILVER, TilePictures.dragonOf(c))
        for (c in listOf("normal", "trivial", "minus", "n", "t", "m", "", null)) assertNull("$c", TilePictures.dragonOf(c))
        assertEquals("elite_gold", TilePictures.Dragon.GOLD.kit)
        assertEquals("elite_silver", TilePictures.Dragon.SILVER.kit)
    }

    @Test fun theKitAsksForTheGamesDragons() {
        val f = listOf(File("../companion/app/src/main/assets/harvest/kit.json"), File("companion/app/src/main/assets/harvest/kit.json"))
            .first { it.canRead() }
        val m = JSONObject(f.readText()).getJSONObject("members")
        assertEquals("elite_gold", m.optString("nameplates-icon-elite-gold"))
        assertEquals("elite_silver", m.optString("nameplates-icon-elite-silver"))
        assertTrue("the kit step's version bumped, so phones build the kit again", KitSteps.Kit.version >= 5)
    }

    /** a kit with flat stand-ins: gold = pure magenta, silver = pure cyan, so the pictures' pixels say which was drawn */
    private fun flatKit(): File {
        val root = Files.createTempDirectory("elite-kit").toFile()
        val kit = File(root, "ui/kit").apply { mkdirs() }
        for ((n, col) in listOf("elite_gold" to Color.MAGENTA, "elite_silver" to Color.CYAN)) {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(col) }
            File(kit, "$n.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return root
    }

    private fun picture(id: String, w: Int = 438, h: Int = 220): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        assertTrue(id, TilePictures.draw(Canvas(b), app, id, RectF(0f, 0f, w.toFloat(), h.toFloat()), TilePictures.Look()))
        return b
    }

    private fun count(b: Bitmap, col: Int): Int {
        var n = 0
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (p in px) if (Color.red(p) == Color.red(col) && Color.green(p) == Color.green(col) && Color.blue(p) == Color.blue(col)) n++
        return n
    }

    @Test fun theNameplatesPictureWearsAGoldAndASilverDragon() {
        art(flatKit())
        val b = picture(GameUi.NP_ID)
        assertTrue("an elite's gold dragon over its plate (${count(b, Color.MAGENTA)} px)", count(b, Color.MAGENTA) > 100)
        assertTrue("a rare's silver dragon over its plate (${count(b, Color.CYAN)} px)", count(b, Color.CYAN) > 100)
    }

    @Test fun theTargetBarPictureHasTheElitesDragonBehindItsLevelBox() {
        art(flatKit())
        val b = picture(GameUi.UFT_ID)
        val gold = count(b, Color.MAGENTA)
        assertTrue("the gold dragon behind the level box ($gold px)", gold > 200)
        assertEquals("no silver on an elite's bar", 0, count(b, Color.CYAN))
        // behind the box: the box's own middle stays dark (the dragon round it, not over it)
        val k = minOf(438f / TilePictures.W, 220f / TilePictures.H)
        val x0 = 219f - TilePictures.W * k / 2f; val y0 = 110f - TilePictures.H * k / 2f
        val mid = b.getPixel((x0 + 41f * k).toInt(), (y0 + 27f * k).toInt())
        assertTrue("the level box drawn over the dragon (${Integer.toHexString(mid)})", Color.red(mid) < 60 && Color.blue(mid) < 60)
    }

    @Test fun noKitStillDrawsAStandIn() {
        art(null)
        val b = picture(GameUi.NP_ID)
        // the drawn stand-in's gold (232, 184, 60) and silver (200, 205, 214)
        assertTrue("a drawn gold stand-in", count(b, Color.rgb(232, 184, 60)) > 20)
        assertTrue("a drawn silver stand-in", count(b, Color.rgb(200, 205, 214)) > 20)
    }

    @Test fun shots() {
        val dir = System.getenv("ELITE_SHOTS_DIR")?.let { File(it) } ?: return
        dir.mkdirs()
        for ((tag, root) in listOf("kit" to System.getenv("ELITE_ART_ROOT")?.let { File(it) }, "nokit" to null)) {
            if (tag == "kit" && root == null) continue
            art(root)
            for ((nm, id) in listOf("nameplates" to GameUi.NP_ID, "targetbar" to GameUi.UFT_ID)) {
                val b = picture(id, 876, 440)
                File(dir, "tile_${nm}_$tag.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
