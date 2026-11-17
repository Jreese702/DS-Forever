package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * EVERY SHARED-GROUND CALLER, PER THEME (beautify 8.4 / critic C8; the user, 2026-11-17: "the background on some of the OLED themes look
 * lifeless, like the macro and friend room, I do really like having the background image there"): the rooms that stand on UiKit's
 * grounds (pane / windowGround / professionGround / strip) drawn in OLED Bronze, OLED Rose, Dark Silver and Classic, as
 * room_<room>_<theme>.png. Uses only what the base commit already had (Theme.use, the views), so the same file renders the BEFORE.
 *
 * Runs only when GROUNDS_SHOTS_DIR is set. GROUNDS_ART_ROOT = an art root laid out like the app's (ui/kit, fonts, icons.pak);
 * GROUNDS_MACRO_DOCS = Macros' documents (grid.json); GROUNDS_SOCIAL = social_03_friends.json's folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeGroundsShotsTest {
    private val out: File? = System.getenv("GROUNDS_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    private fun artRoot() {
        val root = System.getenv("GROUNDS_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(200); ShadowLooper.idleMainLooper() }

    private fun shot(v: View, h: Int = H): Bitmap {
        lay(v, W, h)
        val b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        for (pass in 0..1) {
            val c = Canvas(b); c.drawColor(Theme.ink)
            v.draw(c)
            if (pass == 0) { settle(); lay(v, W, h) }
        }
        return b
    }

    // ---------------------------------------------------------------- the rooms

    private fun macros(): Bitmap? {
        val f = System.getenv("GROUNDS_MACRO_DOCS")?.let { File(it, "grid.json") }?.takeIf { it.isFile } ?: return null
        val v = MacrosView(app).apply { dryRun = true }
        v.onDoc(JSONObject(f.readText()), live = false)
        return shot(v)
    }

    private fun social(): Bitmap? {
        val f = System.getenv("GROUNDS_SOCIAL")?.let { File(it, "social_03_friends.json") }?.takeIf { it.isFile }
        val v = SocialView(app)
        f?.let { v.onDoc(JSONObject(it.readText())) }
        return shot(v)
    }

    private fun bags(): Bitmap {
        val v = BagsView(app)
        val icons = listOf(134085, 134252, 134830, 134414, 135232, 134566, 134579, 134120, 132832, 132815, 132891, 132912)
        val items = JSONArray()
        for ((k, ic) in icons.withIndex()) items.put(JSONObject().put("bag", 0).put("slot", k + 1).put("id", 2000 + k).put("icon", ic)
            .put("count", if (k % 3 == 0) 5 else 1).put("q", k % 5).put("name", "Item $k"))
        v.update(JSONObject().put("free", 14).put("total", 26).put("money", 102345)
            .put("bags", JSONArray().put(JSONObject().put("bag", 0).put("slots", 16)).put(JSONObject().put("bag", 1).put("slots", 10).put("icon", 133628)))
            .put("items", items), false)
        return shot(v)
    }

    private fun chat(): Bitmap {
        val v = ChatView(app) { }
        val lines = JSONArray()
        val said = listOf(Triple("say", "Brannock", "anyone up for the Deadmines?"), Triple("party", "Mirelle", "need one more healer for DM"),
            Triple("whisper", "Thrall", "are you still selling the bracers?"), Triple("guild", "Sennah", "grats on 20!"),
            Triple("say", "Korvash", "on my way"))
        val cls = listOf("WARRIOR", "PRIEST", "SHAMAN", "MAGE", "SHAMAN")
        for ((i, s) in said.withIndex()) lines.put(JSONObject().put("i", i + 1).put("ch", s.first).put("who", s.second).put("text", s.third)
            .put("cls", cls[i]).put("chan", "").put("idx", 0).put("style", ""))
        lines.put(JSONObject().put("i", 6).put("ch", "loot").put("who", "").put("text", "You receive loot: [Linen Cloth]x2.").put("style", "plain")
            .put("cls", "").put("chan", "").put("idx", 0))
        v.update(JSONObject().put("v", "chat37").put("s", 77L).put("n", 6).put("lines", lines)
            .put("can", JSONObject().put("party", true).put("guild", true).put("raid", false).put("chans", JSONArray())))
        return shot(v)
    }

    private fun gameNow(): Long {
        val c = java.util.Calendar.getInstance()
        val before = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = before[c.get(java.util.Calendar.MONTH)] + c.get(java.util.Calendar.DAY_OF_MONTH)
        return ((doy * 24L + c.get(java.util.Calendar.HOUR_OF_DAY)) * 60L + c.get(java.util.Calendar.MINUTE)) * 60_000L +
            c.get(java.util.Calendar.SECOND) * 1000L + c.get(java.util.Calendar.MILLISECOND)
    }

    private fun vendor(): Bitmap {
        val items = JSONArray()
        val goods = listOf(Triple("Refreshing Spring Water", 132794, 25), Triple("Ice Cold Milk", 132815, 62), Triple("Rough Arrow", 132382, 10),
            Triple("Light Shot", 132384, 10), Triple("Small Quiver", 134407, 400), Triple("Tough Jerky", 133972, 25))
        for ((k, g) in goods.withIndex()) items.put(JSONObject().put("i", k + 1).put("id", 159 + k).put("name", g.first).put("icon", g.second)
            .put("price", g.third.toLong()).put("stack", 5).put("avail", -1).put("buyable", true).put("usable", true).put("max", 20).put("q", 1).put("slot", k))
        val m = JSONObject().put("npc", JSONObject().put("name", "Innkeeper Farley")).put("items", items).put("sell", JSONArray())
            .put("buyback", JSONArray()).put("money", 123456L).put("qtyMax", 20).put("canRepair", true).put("needsRepair", true).put("repairCost", 845L)
            .put("act", JSONObject().put("repair", 1).put("junk", 2).put("back", 3))
        val d = JSONObject().put("type", "interact").put("iv", "i160").put("merchant", m).put("mode", true).put("_gt", gameNow())
            .put("nav", JSONObject().put("on", true).put("n", 1).put("tab", "buy"))
        val v = InteractView(app); v.dryRun = true
        v.update(d, true)
        return shot(v, 1080)
    }

    private fun loot(): Bitmap {
        val v = InteractView(app); v.dryRun = true
        val gn = InteractView::class.java.getDeclaredMethod("gameNow").apply { isAccessible = true }
        val loot = JSONObject().put("free", 9).put("items", JSONArray()
            .put(JSONObject().put("i", 1).put("icon", 133784).put("name", "24 Copper").put("count", 0).put("q", 0).put("coin", true))
            .put(JSONObject().put("i", 2).put("icon", 135274).put("name", "Cruel Barb").put("count", 1).put("q", 3).put("id", 5191))
            .put(JSONObject().put("i", 3).put("icon", 132889).put("name", "Linen Cloth").put("count", 3).put("q", 1).put("id", 2589)))
            .put("act", JSONObject().put("all", 9))
        val d = JSONObject().put("type", "interact").put("iv", "i162").put("mode", true).put("holder", "npc").put("bnext", "home")
            .put("loot", loot).put("nav", JSONObject().put("on", true).put("cur", "loot 1")).put("_gt", gn.invoke(v) as Long)
            .put("pad", JSONObject().put("rows", JSONObject().put("ibuy", 15).put("iact", 14).put("ipick", 13)))
        v.update(d, true)
        return shot(v, 1080)
    }

    private fun layout(): Bitmap? {
        val f = listOf(File("../dsforever/src/test/resources/layout-fixtures"), File("dsforever/src/test/resources/layout-fixtures"))
            .map { File(it, "lay_game.json") }.firstOrNull { it.isFile } ?: return null
        LayoutModel.Live.clearForTest()
        LayoutModel.Live.note(JSONObject(f.readText()))
        val v = LayoutView(app).apply { dryRun = true }
        v.open()
        return shot(v)
    }

    private fun dialog(): Bitmap {
        val v = DialogView(app)
        v.update(JSONObject().put("type", "dialogs").put("list", JSONArray().put(JSONObject().put("i", 1).put("which", "CONFIRM_LEAVE")
            .put("text", "Brannoc invites you to a group.").put("x", false)
            .put("buttons", JSONArray().put(JSONObject().put("k", 1).put("label", "Accept")).put(JSONObject().put("k", 2).put("label", "Decline"))))), false)
        v.visibility = View.VISIBLE
        return shot(v)
    }

    @Test fun shots() {
        assumeTrue(out != null)
        artRoot(); Tr.init(app); Settings.init(app)
        val themes = (System.getenv("GROUNDS_THEMES") ?: "oled_bronze,oled_rose,dark_silver,classic").split(",")
        val rooms = listOf<Pair<String, () -> Bitmap?>>(
            "macros" to ::macros, "social" to ::social, "calendar" to { shot(CalendarView(app)) },
            "groupfinder" to { shot(GroupFinderView(app)) }, "character" to { shot(CharacterView(app)) }, "bags" to ::bags,
            "professions" to { shot(ProfessionsView(app)) }, "talents" to { shot(TalentsView(app) { _, _ -> }) }, "chat" to ::chat,
            "vendor" to ::vendor, "loot" to ::loot, "layout" to ::layout, "dialog" to ::dialog)
        out!!.mkdirs()
        for (id in themes) {
            Theme.use(id)
            for ((name, f) in rooms) {
                val b = runCatching { f() }.onFailure { System.err.println("grounds $id/$name: $it"); it.printStackTrace() }.getOrNull() ?: continue
                File(out, "room_${name}_$id.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        Theme.use("classic")
    }
}
