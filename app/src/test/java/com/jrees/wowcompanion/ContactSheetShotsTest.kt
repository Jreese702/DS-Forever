package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
 * THE BEAUTIFY CONTACT SHEET (feat/beautify integration, 2026-11-18): every Settings tab scrolled through (every group open), the Theme
 * maker and its premade gallery, and every room, each in [CONTACT_THEMES] (default Classic Bronze - the lightest preset, there is no light
 * theme -, Dark Silver, OLED Bronze), as <theme>/<what>.png under CONTACT_SHOTS_DIR. The rooms reuse [ThemeGroundsShotsTest]'s builders
 * (its GROUNDS_* documents), plus Home, Quests, the Spellbook and the world map in their empty / sample states.
 *
 * Runs only when CONTACT_SHOTS_DIR is set; CONTACT_ART_ROOT = an art root laid out like the app's. A render only: no assertion, run alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactSheetShotsTest {
    private val out: File? = System.getenv("CONTACT_SHOTS_DIR")?.let { File(it) }
    private val app get() = RuntimeEnvironment.getApplication()
    private val W = 1240
    private val H = 1080 - Strip.HEIGHT

    @After fun forget() {
        Theme.use("classic")
        GeneralTiles.onOpenTheme = null
        ThemeMakerPage.forget()
    }

    private fun artRoot() {
        val root = System.getenv("CONTACT_ART_ROOT") ?: return
        ArtHarvest::class.java.getDeclaredField("rootDir").apply { isAccessible = true }.set(null, File(root))
    }

    private fun lay(v: View, w: Int = W, h: Int = H) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun settle() { ShadowLooper.idleMainLooper(); Thread.sleep(150); ShadowLooper.idleMainLooper() }

    private fun draw(v: View, h: Int = H): Bitmap {
        val b = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); v.draw(c)
        return b
    }

    private fun save(dir: File, name: String, b: Bitmap) {
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun room(): SettingsRoom {
        Settings.init(app); SettingsTabs.init(app); GameUi.init(app)
        val sv = SettingsView(app) { }
        sv.loginRow = true
        return SettingsRoom(app, sv) { null }
    }

    /** one tab, every group open, page by page down its scroll */
    private fun tab(dir: File, t: SettingsTabs.Tab) {
        val r = room()
        r.show(t)
        lay(r); settle()
        for (g in SettingsTabs.groups(t)) if (g !in r.open && r.hasRows(g)) r.toggle(g)
        lay(r); settle(); lay(r)
        val scroll = SettingsRoom::class.java.getDeclaredField("scroll").apply { isAccessible = true }.get(r) as ScrollView
        val total = scroll.getChildAt(0).height
        val step = (scroll.height - 140).coerceAtLeast(200)
        var y = 0; var i = 1
        while (true) {
            scroll.scrollTo(0, y); lay(r)
            save(dir, "settings_${t.name.lowercase()}_$i", draw(r))
            if (y + scroll.height >= total || i >= 12) break
            y += step; i++
        }
    }

    private fun maker(dir: File) {
        val r = room()
        r.show(SettingsTabs.Tab.GENERAL)
        lay(r); settle()
        r.openThemeMaker()
        lay(r); settle(); lay(r)
        save(dir, "theme_maker", draw(r))
        r.themeMaker.key("PADDUP"); r.themeMaker.key("PAD1")     // Up from Accent: Premade themes; A opens the gallery
        lay(r); settle(); lay(r)
        save(dir, "theme_gallery", draw(r))
    }

    // ---------------------------------------------------------------- the rooms

    private val grounds = ThemeGroundsShotsTest()
    private fun g(name: String): Bitmap? = runCatching {
        ThemeGroundsShotsTest::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(grounds) as Bitmap?
    }.getOrNull()

    private fun shot(v: View, h: Int = H): Bitmap {
        lay(v, W, h); draw(v, h); settle(); lay(v, W, h)
        return draw(v, h)
    }

    private fun home(): Bitmap {
        val holder = FrameLayout(app)
        val map = MapView(app); val cast = CastView(app) { }; val chat = ChatView(app) { }
        holder.addView(map); holder.addView(cast); holder.addView(chat)
        val home = HomeView(app, map, cast, chat) { }
        home.borrow()
        lay(home)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        val n = JSONArray()
        for (p in 1..8) n.put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1]))
        cast.update(JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1)
            .put("pages", JSONArray().put(true).put(true).put(true)).put("L", JSONObject().put("n", n))))
        home.tick()
        // the clock test's settle: without the preview look Home draws nothing off the device (the first sheet's black Home)
        val look = HomeView.Companion.Look(HomeRest.DIAMONDS, false, false, false)
        HomeView::class.java.methods.firstOrNull { it.name.startsWith("previewSettle") }?.invoke(home, look)
        home.onFull(JSONObject().put("type", "full").put("actionbar", JSONObject().put("hud", JSONObject().put("ok", true)))
            .put("bags", JSONObject().put("money", 1_234_567L).put("freeGeneral", 14).put("free", 14).put("total", 66)
                .put("equipped", JSONObject().put("slots", JSONArray()))), true)
        return shot(home)
    }

    /** QuestRewardsShotsTest's three quests, so the log shows its list and detail panes */
    private fun quests(): Bitmap {
        val a = JSONArray()
        a.put(JSONObject().put("id", 33).put("title", "Wolves Across the Border").put("zone", "Elwynn Forest").put("lv", 6).put("slot", 1)
            .put("watch", 1).put("focus", true)
            .put("obj", JSONArray().put(JSONObject().put("t", "Tough Wolf Meat: 8/8").put("have", 8).put("need", 8).put("f", true)))
            .put("ready", true)
            .put("desc", "Those wolves are a menace. Bring me eight pieces of Tough Wolf Meat and I'll see that you're well paid.")
            .put("rw", JSONObject().put("m", 2575).put("xp", 450)))
        a.put(JSONObject().put("id", 46).put("title", "Bounty on Murlocs").put("zone", "Elwynn Forest").put("lv", 8).put("slot", 2).put("watch", 1)
            .put("obj", JSONArray().put(JSONObject().put("t", "Torn Murloc Fin: 3/8").put("have", 3).put("need", 8)))
            .put("rw", JSONObject().put("m", 600).put("xp", 750)))
        a.put(JSONObject().put("id", 7).put("title", "Kobold Camp Cleanup").put("zone", "Northshire").put("lv", 3).put("slot", 3)
            .put("obj", JSONArray().put(JSONObject().put("t", "Kobold Vermin slain: 4/10").put("have", 4).put("need", 10))))
        val v = QuestsView(app, { null }, { })
        v.update(JSONObject().put("list", a).put("plv", 7).put("maxQ", 25).put("rwMode", "id"))
        return shot(v)
    }

    @Test fun contactSheet() {
        assumeTrue(out != null)
        artRoot(); Tr.init(app); Settings.init(app)
        val themes = (System.getenv("CONTACT_THEMES") ?: "classic,dark_silver,oled_bronze").split(",")
        val rooms = listOf<Pair<String, () -> Bitmap?>>(
            "home" to ::home,
            "quests" to ::quests, "quests_wait" to { shot(QuestsView(app, { null }, { })) },
            "spellbook" to { shot(SpellbookView(app, onBind = { })) },
            "talents" to { shot(TalentsView(app) { _, _ -> }) },
            "worldmap" to { shot(WorldMapRoomView(app)) },
            "macros" to { g("macros") }, "social" to { g("social") }, "calendar" to { shot(CalendarView(app)) },
            "groupfinder" to { shot(GroupFinderView(app)) }, "character" to { shot(CharacterView(app)) }, "bags" to { g("bags") },
            "professions" to { shot(ProfessionsView(app)) }, "chat" to { g("chat") }, "vendor" to { g("vendor") },
            "loot" to { g("loot") }, "dialog" to { g("dialog") }, "layout" to { g("layout") }, "controller" to { g("controller") })
        for (id in themes) {
            Theme.use(id)
            val dir = File(out, id)
            for (t in SettingsTabs.Tab.values()) if (!t.hidden)
                runCatching { tab(dir, t) }.onFailure { System.err.println("contact $id/${t.name}: $it"); it.printStackTrace() }
            runCatching { maker(dir) }.onFailure { System.err.println("contact $id/maker: $it"); it.printStackTrace() }
            for ((name, f) in rooms) {
                val b = runCatching { f() }.onFailure { System.err.println("contact $id/$name: $it"); it.printStackTrace() }.getOrNull() ?: continue
                save(dir, "room_$name", b)
            }
        }
    }
}
