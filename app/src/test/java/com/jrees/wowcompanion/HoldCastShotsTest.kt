package com.jrees.wowcompanion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * HOLD TO CAST, THE REAL RENDERS (PadMap m190 / m194; the user's picks 2026-11-24: "A: corner badge", and on test 1529 "Yes, rebuild it
 * that way" - the hold spell fires when you let go after the hold time): the Controller room's Hold row and its hint ("0.6 s, then let
 * go"; WoW Defaults, In combat, a base face, an add-on that says off, the hold chooser; es / fr), its left page's hold badges; Home's
 * band and diamonds with the badge and its own sweep (both ready, the tap cooling, the hold cooling), the charge ring round the badge while a kept press is
 * held (HoldCast.watch: the band's own reading of the pad, timed to the add-on's looks) and the flash when a release runs the hold; the
 * Bind box's foot line; Settings' Hold time picture. Pixel checks prove the badge, the arc and the flash are drawn; with HOLD_SHOTS_DIR
 * set the renders are saved there (CLOCK_ART_ROOT: the art root, laid out like the app's).
 * ROUND 1533 (PadMap m199; the user on test 1532, 2026-11-25: "The red outline is an eyesore we should just shade the ability like we
 * do with the glyphs. And same with the charge up dots, it should be a glowing ring around the skill. When the ability is casting it
 * should show the ability with a golden ring, a great example of this is raptor strikes normal behavior ... I also want this to cast
 * right when the hold bar is filled"): the fire is now the launcher's at the fire point (the add-on's keep word, HoldCast.fireNow), the
 * takeover's face is drawn CHECKED (the game's gold frame_*_selected ring), the badge's state is a shade of its icon, the charge ring
 * glows; [round1533Shots] renders the spec's set (home_badge_states, home_ring_*, home_raptor_queued_*, home_instant_*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldCastShotsTest {
    private val out: File? = System.getenv("HOLD_SHOTS_DIR")?.let { File(it).also { d -> d.mkdirs() } }
    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 1240
    private val h = 960

    @Before fun up() {
        System.getenv("CLOCK_ART_ROOT")?.let { root ->
            val f = ArtHarvest::class.java.getDeclaredField("rootDir"); f.isAccessible = true; f.set(null, File(root))
        }
        HomeSettings.init(app); CastSettings.init(app)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    @After fun down() {
        Tr.setChoice(app, "auto")
        HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.takeover.clear()
        HoldCast.padReset(true); HoldCast.release.cn = null; HoldCast.release.ft = null
    }

    private val book = """{"type":"spells","v":"s9","s":{"lines":[{"k":1,"n":"General","i":136048},{"k":2,"n":"Restoration","i":136052}],
        "items":[{"n":"Healing Wave","t":1,"id":331,"a":331,"sl":1,"ln":2,"i":136052},{"n":"Lightning Bolt","t":1,"id":403,"a":403,"sl":2,"ln":2,"i":136048},
        {"n":"Chain Heal","t":1,"id":1064,"a":1064,"sl":3,"ln":2,"i":136042},{"n":"Chain Lightning","t":1,"id":421,"a":421,"sl":4,"ln":2,"i":136015}],
        "win":{"croom":5,"open":0,"close":1,"bfon":12,"bfoff":13}}}"""

    /** LT + Y: Healing Wave (In combat) over Healing Wave, hold Chain Heal; LT + A: Lightning Bolt, hold Chain Lightning; the bare A
     *  (the game's Jump) hold Lightning Shield; bare D-pad Up: Earth Shock, hold Frost Shock */
    private fun padmap(holdOn: Boolean = true): JSONObject {
        fun h(n: String, id: Int, i: Int) = JSONObject().put("n", n).put("id", id).put("i", i)
        val sets = JSONArray()
        for (set in 1..4) {
            val a = JSONArray()
            for (p in 1..8) a.put(JSONObject().put("pos", p).put("kind", if (set == 1 && p >= 5) "fixed" else "none").put("s", (set - 1) * 12 + p))
            when (set) {
                1 -> {
                    a.put(1, JSONObject().put("pos", 2).put("s", 2).put("kind", "spell").put("n", "Earth Shock").put("id", 8042).put("i", 136026)
                        .put("h", h("Frost Shock", 8056, 135849)))
                    a.put(7, JSONObject().put("pos", 8).put("kind", "fixed").put("n", "Jump").put("h", h("Lightning Shield", 324, 136051)))
                }
                2 -> {
                    a.put(5, JSONObject().put("pos", 6).put("s", 18).put("kind", "combat").put("ov", true).put("base", "spell")
                        .put("c", h("Healing Wave", 331, 136052)).put("o", h("Lesser Healing Wave", 8004, 136043)).put("h", h("Chain Heal", 1064, 136042))
                        .put("hc", h("Lightning Shield", 324, 136051)))          // PER TAB: In combat's own hold
                    a.put(7, JSONObject().put("pos", 8).put("s", 20).put("kind", "spell").put("n", "Lightning Bolt").put("id", 403).put("i", 136048)
                        .put("h", h("Chain Lightning", 421, 136015)))
                }
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = JSONObject().put("page", 1).put("pages", 3).put("sets", sets).put("face", JSONObject().put("cc", true))
            .put("hold", JSONObject().put("on", holdOn))
        return JSONObject().put("type", "padmap").put("v", "m194").put("m", m)
    }

    private fun ctl(view: String, set: Int, pos: Int, chooser: JSONObject? = null): PadMapModel.Ctl {
        val top = PadMapModel.topOf(view)
        val c = JSONObject().put("on", true).put("view", view).put("top", top).put("focus", "buttons").put("pos", pos)
            .put("tcur", PadMapModel.TABS.indexOf(top) + 1).put("set", set).put("shown", set).put("lay", "swap").put("cbpn", 0).put("cbps", 1)
        if (chooser != null) c.put("chooser", chooser)
        return PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p59").put("c", c))!!
    }

    private fun room(holdOn: Boolean = true): ControllerView {
        val v = ControllerView(app, { true })
        v.update(SpellsModel.parse(JSONObject(book))!!)
        v.onPadMap(PadMapModel.parse(padmap(holdOn))!!)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        return v
    }

    private fun save(b: Bitmap, name: String) { out?.let { File(it, "$name.png").outputStream().use { o -> b.compress(Bitmap.CompressFormat.PNG, 100, o) } } }

    private fun shot(v: View, name: String, ww: Int = w, hh: Int = h): Bitmap {
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink)
        v.draw(c); c.drawColor(Theme.ink); v.draw(c)                          // twice: the first frame settles
        save(b, name)
        return b
    }

    private fun lang(l: String) {
        if (l == "en") { Tr.setChoice(app, "auto"); return }
        listOf(File("../companion/app/src/main/assets/companion/i18n/$l.json"), File("companion/app/src/main/assets/companion/i18n/$l.json"))
            .firstOrNull { it.canRead() }?.let { Tr.load(l, it.readText()) }
        Tr.setChoice(app, l)
    }

    /** how many pixels in [r] are gold-ish (the hold's arc / the lit badge ring / the fired ring) */
    private fun gold(b: Bitmap, r: RectF): Int {
        var n = 0
        for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(b.height))
            for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(b.width)) {
                val c = b.getPixel(x, y)
                // (round 1533: the game's checked ring is a deeper gold, blue ~40: any warm gold counts)
                if (Color.red(c) > 190 && Color.green(c) > 150 && Color.blue(c) < 190 && Color.red(c) > Color.blue(c) + 60) n++
            }
        return n
    }
    /** round 1533: the takeover's gold ring - the pixels that turned gold between [base] and [take] lying on a ring (within 0.7 - 1.05
     *  of their 90th-percentile distance from their box's centre: the checked ring is the outermost gold the takeover draws) */
    private fun ringGold(base: Bitmap, take: Bitmap): Int {
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until base.height) for (x in 0 until base.width) if (isGoldPx(take.getPixel(x, y)) && !isGoldPx(base.getPixel(x, y))) { xs += x; ys += y }
        if (xs.size < 20) return 0
        val cx = (xs.min() + xs.max()) / 2.0; val cy = (ys.min() + ys.max()) / 2.0
        val ds = xs.indices.map { Math.hypot(xs[it] - cx, ys[it] - cy) }.sorted()
        val r = ds[(ds.size * 0.9).toInt()]
        return ds.count { it >= r * 0.7 && it <= r * 1.05 }
    }
    /** a warm gold (the checked ring's art, also where it is dimmer at its rim) */
    private fun isGoldPx(c: Int) = Color.red(c) > 140 && Color.green(c) > 110 && Color.blue(c) < 120 && Color.red(c) > Color.blue(c) + 70
    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) return true
        return false
    }

    @Test fun theControllerRoom() {
        for (l in listOf("en", "es", "fr")) {
            lang(l)
            val sfx = if (l == "en") "" else "_$l"
            val v = room()
            v.onPadTabCtl(ctl("normal", 2, 6)); shot(v, "r1_room_defaults_lt_y_hold_row$sfx")
            if (l != "en") continue
            v.onPadTabCtl(ctl("combat", 2, 6)); shot(v, "r2_room_in_combat_lt_y_hold_row")
            v.onPadTabCtl(ctl("normal", 1, 8)); shot(v, "r3_room_base_a_hold_row")
            v.onPadTabCtl(ctl("normal", 2, 8)); shot(v, "r4_room_lt_a_hold_row")
            v.onPadTabCtl(ctl("normal", 1, 6)); shot(v, "r5_room_base_y_no_hold_bind_hold")
            v.onPadTabCtl(ctl("normal", 2, 7)); shot(v, "r10_room_empty_button_no_hold")
            v.onPadTabCtl(ctl("normal", 2, 6, JSONObject().put("list", "spells").put("row", 3).put("id", "1064").put("hold", true)))
            shot(v, "r6_room_hold_chooser")
        }
        lang("en")
        // m194: no probe - the Hold row whenever the add-on has hold to cast (an add-on that says off: no row)
        val off = room(holdOn = false)
        off.onPadTabCtl(ctl("normal", 2, 6)); val b1 = shot(off, "r7_room_addon_says_off_no_row")
        val on = room(); on.onPadTabCtl(ctl("normal", 2, 6)); val b3 = shot(on, "r9_room_hold_row")
        assertTrue("the Hold row and its hint show with hold to cast on", differs(b1, b3))
        for (t in CastSettings.HoldTime.values()) {
            CastSettings.holdTime = t
            val v = room(); v.onPadTabCtl(ctl("normal", 2, 6)); shot(v, "r11_room_hold_hint_${t.name.lowercase()}")
        }
        CastSettings.holdTime = CastSettings.HoldTime.NORMAL
    }

    /**
     * THE int/1533 REVIEW (layout): the Hold row's hint ("%s, casts when full") ran off the pane - es "0.7 s, se lanza al" and fr "0.7 s,
     * se lance u", cut at the view's edge, en over the page margin. Now it is whole and inside the pane's text column in every language:
     * after the Hold mark while it fits there at 20 px or more (en), else on its own line under the hold spell's name (es / fr)
     */
    @Test fun theHoldHintFitsThePane() {
        for (l in listOf("en", "es", "fr")) {
            lang(l)
            val pd = UiKit.inkPaint(24f)
            val text = Tr.t(PadTabPage.HOLD_POINT.replace("%s", HoldCast.holdWord()))
            val holdW = UiKit.inkPaint(24f).measureText(Tr.t("Hold"))
            // round 1533's own: at 24 px right after the mark (x0 + 112 + Hold + 22, + 22), the pane's text edge w - 56
            val inlineX = 740f + 40f + 112f + holdW + 44f
            if (l != "en") assertTrue("$l: 1533's hint ran past the view's edge (${pd.measureText(text)} px from $inlineX)",
                inlineX + pd.measureText(text) > w)
            val hh = PadTabPage.holdHint(text, pd, holdW, w.toFloat())
            assertEquals("$l: the whole hint, never cut", text, hh.text)
            assertTrue("$l: ${hh.size} px", hh.size >= PadTabPage.HOLD_HINT_MIN_PX)
            assertTrue("$l: inside the pane's text column", hh.x + UiKit.inkPaint(hh.size).measureText(hh.text) <= w - 56f)
            if (l == "en") { assertEquals("en: after the mark", 34f, hh.dy); assertTrue(hh.size >= PadTabPage.HOLD_HINT_INLINE_MIN_PX) }
            else assertEquals("$l: its own line under the hold spell's name", PadTabPage.HOLD_HINT_LINE, hh.dy)
        }
        lang("en")
    }

    /** Home's bars (Cast) with the hold badges; [prog] / [fire]: the bare A held [prog] ms (its arc) / let go past the hold point (the
     *  flash), through the band's own pad reading (CastView's onButtons) and HoldCast.watch, as on the device */
    /** m195: [states] the hold spell's (Lightning Shield, 324) state as the add-on's hudS.fs says it ("mana" / "range" / "both" /
     *  round 1533 "unusable" / "unusable_range"); [take] after the fire: "cast" (mid a 1.8 s cast), "gcd" (an instant, inside its GCD),
     *  "back" (all of it over); round 1533: "q0" / "q25" (queued for the next swing: at the fire / 2.5 s on), "swing" (its swing landed,
     *  + 0.5 s), "gcd01" / "gcd12" / "gcd17" (an instant with a 1.5 s GCD, 0.1 / 1.2 / 1.7 s on), "nogcd01" / "nogcd04" (no GCD: none) */
    private fun bars(name: String, lt: Boolean, layout: CastView.Layout?, tapCd: Boolean = false, holdCd: Boolean = false,
                     prog: Long = -1L, fire: Boolean = false, states: String? = null, take: String? = null, ltUp: Boolean = false,
                     refsAfter: Map<Int, PadMapModel.Ref>? = null, tap: JSONObject? = null, tapIcon: Int? = null): Bitmap {
        val cast = CastView(app) { }
        if (layout != null) cast.layout = layout
        // 1:1 with the Thor's bottom screen: the band 1240 wide (its 112-unit row at rest, 215 px with its room), the diamonds 354 tall
        val ww = 1240; val hh = if (layout == CastView.Layout.DIAMONDS) 354 else 215
        cast.measure(View.MeasureSpec.makeMeasureSpec(ww, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        cast.layout(0, 0, ww, hh)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, tapIcon ?: 136075)
        fun layer() = JSONArray().apply { for (p in 1..8) put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1])) }
        val hud = JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1).put("pages", JSONArray().put(true).put(true).put(true))
            .put("L", JSONObject().put("n", layer()).put("lt", layer()).put("rt", layer()).put("lr", layer())))
        cast.update(hud)
        // the icons decode off the UI thread (ART_EXEC) and come back through the main looper: let them land, then lay the hud again
        for (i in 0 until 20) { Thread.sleep(25); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        cast.update(hud)
        val map = PadMapModel.parse(padmap())!!
        cast.holdRefs = PadMapModel.holdRefs(map)
        val now = SystemClock.elapsedRealtime()
        if (holdCd) cast.holdCooldowns(mapOf(HoldCast.key(if (lt) 1 else 0, if (lt) 8 else 8) to PadMapModel.FaceCd(10.0, 6.5, false, false),
            HoldCast.key(0, 2) to PadMapModel.FaceCd(20.0, 15.0, false, false)), now)
        if (tapCd) cast.fightCooldown(8, now - 2000L, 8000L)
        if (states != null || tap != null) {
            val hs = JSONObject().put("g", 500.0)
            if (states != null) {
                val (u, m, r) = when (states) {
                    "mana" -> Triple(1, 1, 1); "range" -> Triple(1, 0, 0); "both" -> Triple(1, 1, 0)
                    "unusable" -> Triple(0, 0, 1); "unusable_range" -> Triple(0, 0, 0); else -> Triple(1, 0, 1)
                }
                hs.put("fs", JSONArray().put(JSONObject().put("i", 324).put("u", u).put("m", m).put("r", r)))
            }
            // round 1533: the tap's own state on the bare A (the state document's n layer, slot 8)
            if (tap != null) hs.put("L", JSONObject().put("n", JSONArray().apply { for (p in 1..8) put(if (p == 8) tap else JSONObject()) }))
            cast.update(JSONObject().put("hudS", hs))
        }
        CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
            .apply { isAccessible = true }.invoke(cast, lt, false, false, false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        // the add-on keeps the A's press on this layer now (its holdmap); the band's own pad reading: A down, held, (let go)
        HoldCast.watch.reset(); HoldCast.watch.map = setOf(HoldCast.key(if (lt) 1 else 0, 8))
        val buttons = CastView::class.java.getDeclaredMethod("onButtons", Int::class.java).apply { isAccessible = true }
        val aBit = 1 shl PadInput.SLOT_FACE_BOTTOM
        HoldCast.takeover.clear()
        // round 1533: the add-on's clock (Normal, 3 looks) and the game at 60 fps: the fire point F = 678 ms
        HoldCast.release.cn = 3; HoldCast.release.ft = 17
        HoldCast.schedule = { }; HoldCast.later = { _, _ -> }; HoldCast.gates = { true }
        if (prog >= 0 || fire) {
            buttons.invoke(cast, aBit)
            if (fire) {
                // the launcher writes A's down (bit 0), the add-on keeps it (its keep word), the fire point comes: A up for the game
                HoldCast.padIn(1, false, false, false, false, SystemClock.uptimeMillis())
                HoldCast.onFacecast(JSONObject().put("type", "facecast").put("pos", 8).put("hk", "keep").put("kind", "holdkeep")
                    .put("lay", if (lt) "lt" else "bare").put("key", "PAD1").put("need", 3))
                ShadowSystemClock.advanceBy(Duration.ofMillis(HoldCast.fireMs(3, 17)))
                HoldCast.fireNow(SystemClock.uptimeMillis())
                ShadowSystemClock.advanceBy(Duration.ofMillis(40))
                // let go a moment later: nothing new for the game (the fire was at the fill)
                buttons.invoke(cast, 0); HoldCast.padIn(0, false, false, false, false, SystemClock.uptimeMillis())
            } else ShadowSystemClock.advanceBy(Duration.ofMillis(prog))
        }
        // the 1532 audit: the band draws the fired spell once (its first frame), then the hold refs change (a fight's In combat tab)
        if (refsAfter != null) {
            val c0 = Canvas(Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)); cast.draw(c0)
            cast.holdRefs = refsAfter
        }
        // ruling A: the trigger let go after the fire - the band back on the bare layer
        if (ltUp) {
            CastView::class.java.getDeclaredMethod("onPad", Boolean::class.java, Boolean::class.java, Boolean::class.java, Boolean::class.java)
                .apply { isAccessible = true }.invoke(cast, false, false, false, false)
            ShadowSystemClock.advanceBy(Duration.ofMillis(120))
        }
        // m195: the takeover's course, as the add-on's holdcast and the state document's GCD say it
        val tk = { st: String, d: Double -> HoldCast.onHoldCast(JSONObject().put("type", "holdcast").put("pos", 8).put("st", st).put("d", d)) }
        when (take) {
            "cast" -> { tk("cast", 1.8); ShadowSystemClock.advanceBy(Duration.ofMillis(700)) }
            "gcd" -> {
                tk("ok", 0.0)
                cast.update(JSONObject().put("hudS", JSONObject().put("g", 500.0).put("gd", JSONObject().put("s", 500.0).put("d", 1.5))))
                ShadowSystemClock.advanceBy(Duration.ofMillis(600))
            }
            "back" -> { tk("ok", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(2600)) }
            "q0" -> { tk("q", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(60)) }
            "q25" -> { tk("q", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(2500)) }
            "swing" -> { tk("q", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(2500)); tk("ok", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(500)) }
            "gcd01", "gcd12", "gcd17" -> {
                // the GCD the fire started (1.5 s from the fire, 0.04 s of it gone)
                HoldCast.onHoldCast(JSONObject().put("type", "holdcast").put("pos", 8).put("st", "gcd").put("gl", 1.46))
                ShadowSystemClock.advanceBy(Duration.ofMillis(when (take) { "gcd01" -> 60L; "gcd12" -> 1160L; else -> 1660L }))
            }
            "nogcd01" -> ShadowSystemClock.advanceBy(Duration.ofMillis(60))
            "nogcd04" -> { ShadowSystemClock.advanceBy(Duration.ofMillis(110)); tk("none", 0.0); ShadowSystemClock.advanceBy(Duration.ofMillis(250)) }
        }
        val b = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Theme.ink); cast.draw(c)
        save(b, name)
        if (prog >= 0 && !fire) buttons.invoke(cast, 0)
        HoldCast.watch.reset(); HoldCast.watch.map = emptySet(); HoldCast.takeover.clear()
        return b
    }

    @Test fun homesBandAndDiamonds() {
        lang("en")
        for ((tag, lay) in listOf("band" to CastView.Layout.ROW, "diamonds" to CastView.Layout.DIAMONDS)) {
            val ready = bars("h1_${tag}_both_ready", lt = false, layout = lay)
            bars("h2_${tag}_tap_cooling", lt = false, layout = lay, tapCd = true)
            val hc = bars("h3_${tag}_hold_cooling", lt = false, layout = lay, holdCd = true)
            val early = bars("h4a_${tag}_held_80ms_no_ring_yet", lt = false, layout = lay, prog = 80L)       // fix/hold-tweaks: the ring from 0.1 s
            val held = bars("h4_${tag}_held_250ms_charge_ring_on_the_badge", lt = false, layout = lay, prog = 250L)
            val charged = bars("h4c_${tag}_held_450ms_charged_the_hold_is_sure", lt = false, layout = lay, prog = 450L)
            val fired = bars("h5_${tag}_fired_at_the_fill_hold_takes_the_face", lt = false, layout = lay, fire = true)
            bars("t1_${tag}_takeover_mid_cast", lt = false, layout = lay, fire = true, take = "cast")
            bars("t2_${tag}_takeover_during_gcd", lt = false, layout = lay, fire = true, take = "gcd")
            val back = bars("t3_${tag}_takeover_back_to_the_tap", lt = false, layout = lay, fire = true, take = "back")
            val mana = bars("s1_${tag}_badge_low_mana", lt = false, layout = lay, states = "mana")
            val range = bars("s2_${tag}_badge_out_of_range", lt = false, layout = lay, states = "range")
            bars("s3_${tag}_badge_low_mana_and_out_of_range", lt = false, layout = lay, states = "both")
            // (fix/hold-tweaks: at 80 ms the press's own pressed look is still easing in on the big face - the ring's place is the badge's)
            val ringAt = diffBox(early, held)?.let { RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                ?: error("$tag: the ring drew nothing at 250 ms")
            assertTrue("$tag: no ring before 0.1 s (far less gold where the ring draws than at 0.25 s; the model: HoldCastTest)",
                gold(early, ringAt) + 40 < gold(held, ringAt))
            assertTrue("$tag: the badge's states change it (mana blue, range red)", differs(ready, mana) && differs(ready, range))
            assertFalse("$tag: the takeover over: the ready picture again", differs(ready, back))
            assertTrue("$tag: charged differs from filling", differs(held, charged))
            bars("h6_${tag}_lt_layer_badges", lt = true, layout = lay, holdCd = true)
            assertTrue("$tag: the hold's own sweep changes the badge", differs(ready, hc))
            val all = RectF(0f, 0f, ready.width.toFloat(), ready.height.toFloat())
            assertTrue("$tag: the held press's charge ring round the badge (gold)", gold(held, all) > gold(ready, all) + 40)
            assertTrue("$tag: the takeover's gold (checked) ring round the big face", ringGold(ready, fired) > 150)
        }
        // nothing held back: exactly the ready picture (no arc, no flash)
        val ready = bars("h7_band_ready_again", lt = false, layout = CastView.Layout.ROW)
        val idle = bars("h7_band_nothing_held_no_arc", lt = false, layout = CastView.Layout.ROW)
        assertFalse("nothing held: no arc", differs(ready, idle))
    }

    /**
     * RULING A (the user's pick 2026-11-25, "Stay until it ends (Recommended)"): LT + A's hold (Chain Lightning) fired, then LT let go -
     * the band back on the bare layer, the bare A's own face (Jump) under it: the takeover STAYS on that main circle (the hold spell's
     * picture in the gold ring) through the cast and the GCD after it, and goes when they end. HOLD_SHOTS_DIR: the renders
     */
    @Test fun anLtHoldKeepsTheMainCircleAfterLtIsLetGo() {
        lang("en")
        for ((tag, lay) in listOf("band" to CastView.Layout.ROW, "diamonds" to CastView.Layout.DIAMONDS)) {
            val all = RectF(0f, 0f, 1240f, if (lay == CastView.Layout.DIAMONDS) 354f else 215f)
            val bare = bars("a0_${tag}_bare_layer_ready", lt = false, layout = lay)
            val held = bars("a1_${tag}_lt_a_hold_fired_lt_held", lt = true, layout = lay, fire = true, take = "cast")
            val up = bars("a2_${tag}_lt_let_go_the_takeover_stays", lt = true, layout = lay, fire = true, take = "cast", ltUp = true)
            val gcd = bars("a3_${tag}_lt_let_go_through_the_gcd", lt = true, layout = lay, fire = true, take = "gcd", ltUp = true)
            val back = bars("a4_${tag}_lt_let_go_back_after_it_ends", lt = true, layout = lay, fire = true, take = "back", ltUp = true)
            // round 1533: the game's gold checked ring round the face (counted in its ring band: the face's own icon changes too)
            assertTrue("$tag: LT held, the takeover's gold ring", ringGold(bare, held) > 150)
            assertTrue("$tag: LT let go mid-cast: the takeover stays on the bare layer's main circle", ringGold(bare, up) > 150)
            assertTrue("$tag: ... and through its GCD", ringGold(bare, gcd) > 150)
            assertFalse("$tag: it ended: the bare layer's ready picture again", differs(bare, back))
        }
    }

    /** THE 1532 AUDIT: the takeover keeps the spell that FIRED - a fight that starts with the hold's own cast hands the band In combat's
     *  hold refs (another spell, or none, on that place), and the face still shows the hold that was cast until it ends */
    @Test fun theTakeoverKeepsTheFiredSpellWhenTheTabChanges() {
        lang("en")
        // In combat's refs: another hold on the bare A (LT + Y's Chain Heal), the rest as they were (the badge faded out meanwhile)
        val combatRefs = PadMapModel.holdRefs(PadMapModel.parse(padmap())!!).toMutableMap().also { it[HoldCast.key(0, 8)] = it.getValue(HoldCast.key(1, 6)) }
        val kept = bars("k1_takeover_fired_spell", lt = false, layout = CastView.Layout.DIAMONDS, fire = true, take = "cast")
        val swapped = bars("k2_takeover_after_the_fight_tab", lt = false, layout = CastView.Layout.DIAMONDS, fire = true, take = "cast",
            refsAfter = combatRefs)
        assertFalse("the fired spell stays on the face (In combat's refs have another there)", differs(kept, swapped))
    }

    // ======================================================================================== round 1533: the spec's shots

    private fun kOf(v: CastView): Float = CastView::class.java.getDeclaredField("k").apply { isAccessible = true }.getFloat(v)
    /** the bounding box of the pixels that differ between [a] and [b] (null: none) */
    private fun diffBox(a: Bitmap, b: Bitmap, pred: (Int) -> Boolean = { true }): android.graphics.Rect? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val q = b.getPixel(x, y)
            if (a.getPixel(x, y) != q && pred(q)) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, bt + 1)
    }
    private fun isGold(c: Int) = Color.red(c) > 200 && Color.green(c) > 150 && Color.blue(c) in 30..200
    /** the old badge's out-of-range ring red (230, 52, 40) */
    private fun isRingRed(c: Int) = Color.red(c) > 190 && Color.green(c) < 90 && Color.blue(c) < 90
    private fun crop(b: Bitmap, r: android.graphics.Rect): Bitmap = Bitmap.createBitmap(b, r.left.coerceAtLeast(0), r.top.coerceAtLeast(0),
        (r.width()).coerceAtMost(b.width - r.left.coerceAtLeast(0)), r.height().coerceAtMost(b.height - r.top.coerceAtLeast(0)))
    private fun strip(name: String, tiles: List<Bitmap>, scale: Int) {
        val w0 = tiles.maxOf { it.width } * scale; val h0 = tiles.maxOf { it.height } * scale
        val out = Bitmap.createBitmap(w0 * tiles.size + 8 * (tiles.size + 1), h0 + 16, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Theme.ink)
        val pnt = android.graphics.Paint().apply { isFilterBitmap = false }
        tiles.forEachIndexed { i, t ->
            c.drawBitmap(t, null, RectF(8f + i * (w0 + 8f), 8f, 8f + i * (w0 + 8f) + t.width * scale, 8f + t.height * scale), pnt)
        }
        save(out, name)
    }

    @Test fun round1533Shots() {
        lang("en")
        val lay = CastView.Layout.DIAMONDS
        // 1. THE BADGE'S STATES: one shade of its icon, no red ring anywhere (ready / mana / unusable / range / mana + range / unusable + range)
        val names = listOf("ready" to null, "mana" to "mana", "unusable" to "unusable", "range" to "range", "mana_range" to "both", "unusable_range" to "unusable_range")
        val shots = names.map { (n, st) -> bars("home_badge_state_$n", lt = false, layout = lay, states = st) }
        val ready = shots[0]; val range = shots[3]
        val box = diffBox(ready, range) ?: error("the out-of-range shade changed nothing")
        val pad = android.graphics.Rect(box.left - 8, box.top - 8, box.right + 8, box.bottom + 8)
        strip("home_badge_states", shots.map { crop(it, pad) }, 1)
        strip("home_badge_states_x3", shots.map { crop(it, pad) }, 3)
        // no red ring: what changes is the ICON's disc only (the shade) - nothing at the badge's border (its brown ring) or outside it
        val bcx = (box.left + box.right) / 2f; val bcy = (box.top + box.bottom) / 2f; val ir = box.width() / 2f
        var outside = 0
        for (x in pad.left until pad.right) for (y in pad.top until pad.bottom) {
            if (x !in 0 until range.width || y !in 0 until range.height) continue
            val d = Math.hypot((x - bcx).toDouble(), (y - bcy).toDouble())
            if (d > ir + 1.5 && range.getPixel(x, y) != ready.getPixel(x, y)) outside++
        }
        assertTrue("no red ring: the out-of-range shade changes nothing outside the icon's disc (the border stays brown) - $outside px", outside <= 4)
        assertFalse("no ring-red pixel at the badge's border", (0 until 32).any { i ->
            val a = Math.PI * 2 * i / 32; val rr = ir / 0.87f * 0.935f
            val x = (bcx + rr * Math.cos(a)).toInt(); val y = (bcy + rr * Math.sin(a)).toInt()
            isRingRed(range.getPixel(x, y)) })
        var dr = 0L; var dgb = 0L; var n = 0
        for (x in box.left until box.right) for (y in box.top until box.bottom) {
            val a = ready.getPixel(x, y); val b = range.getPixel(x, y)
            if (a != b) { dr += Color.red(b) - Color.red(a); dgb += Color.green(b) - Color.green(a) + Color.blue(b) - Color.blue(a); n++ }
        }
        assertTrue("out of range: the badge's ICON is shaded red (red kept, green and blue cut): dR ${dr / n.coerceAtLeast(1)}, dG+dB ${dgb / n.coerceAtLeast(1)}",
            n > 20 && dr / n.toFloat() > -10f && dgb / n.toFloat() < -40f)
        // its border stays brown: the ring's pixels (the badge's outer edge) the same in both
        val cx = (box.left + box.right) / 2; val cy = (box.top + box.bottom) / 2
        val manaRange = shots[4]; val unusableRange = shots[5]
        assertTrue("mana + range: red like range (red is range)", !differs(range, manaRange))
        assertTrue("unusable + range: grey like unusable", !differs(shots[2], unusableRange))
        // 2. THE GLOWING RING: 0 % (just shown), 50 %, 100 % - fix/hold-tweaks: from 0.1 s to the ring's full point R (373 ms at 60 fps:
        // a release before it is the tap, from it the hold), in the game's checked gold, its glow running straight out of it
        val d0 = HoldCast.RING_DELAY_MS
        val f = HoldRelease.ringMs(3, 17)
        val r0 = bars("home_ring_000", lt = false, layout = lay, prog = d0)
        val r50 = bars("home_ring_050", lt = false, layout = lay, prog = d0 + (f - d0) / 2)
        val r100 = bars("home_ring_100", lt = false, layout = lay, prog = f)
        val ringBox = android.graphics.Rect(cx - 60, cy - 60, cx + 60, cy + 60)
        fun count(b: Bitmap, p: (Int) -> Boolean): Int { var k = 0; for (x in ringBox.left until ringBox.right) for (y in ringBox.top until ringBox.bottom)
            if (x in 0 until b.width && y in 0 until b.height && p(b.getPixel(x, y))) k++; return k }
        // (fix/hold-tweaks: the glow is the game's checked gold now, yellower - its soft pixels reach green 230)
        val glowish = { c: Int -> Color.red(c) in 70..250 && Color.green(c) in 50..230 && Color.blue(c) < 120 && Color.red(c) > Color.blue(c) + 40 }
        assertTrue("50 %: the ring's GLOW pixels round the badge (soft gold under the fill)", count(r50, glowish) > count(ready, glowish) + 30)
        assertTrue("50 % fills more than 0 %, 100 % more than 50 %", count(r50, ::isGold) > count(r0, ::isGold) && count(r100, ::isGold) > count(r50, ::isGold))
        // 3. RAPTOR STRIKE'S NORMAL LOOK: queued for the next swing - the face in the game's gold checked ring at the fire, still there at
        // 2.5 s (past 1532's 2 s fallback), gone after the swing's ok (+0.3 s)
        val q0 = bars("home_raptor_queued_t0", lt = false, layout = lay, fire = true, take = "q0")
        val q25 = bars("home_raptor_queued_t2_5s", lt = false, layout = lay, fire = true, take = "q25")
        val sw = bars("home_raptor_queued_swing", lt = false, layout = lay, fire = true, take = "swing")
        assertTrue("queued at the fire: the takeover's gold checked ring", ringGold(ready, q0) > 150)
        assertTrue("2.5 s queued: still there", ringGold(ready, q25) > 150)
        assertFalse("the swing landed: back to the ready picture", differs(ready, sw))
        // the checked ring is gold AT ITS RADIUS: the new gold pixels span the button plus 4 units each side (the game's CheckedTexture)
        val k = kOf(CastView(app) { }.also { v -> v.layout = lay; v.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(354, View.MeasureSpec.EXACTLY)); v.layout(0, 0, 1240, 354) })
        val gb = diffBox(ready, q0) { isGold(it) } ?: error("no new gold")
        assertTrue("the gold ring's span ~ (38 + 8) units (${gb.width()} px at k $k)", gb.width() >= 38f * k && gb.width() <= 60f * k)
        // 4. AN INSTANT WITH A GCD: on the face through the GCD, then back; WITHOUT one: shown, then gone after "none"
        val g01 = bars("home_instant_gcd_t01", lt = false, layout = lay, fire = true, take = "gcd01")
        val g12 = bars("home_instant_gcd_t12", lt = false, layout = lay, fire = true, take = "gcd12")
        val g16 = bars("home_instant_gcd_t17", lt = false, layout = lay, fire = true, take = "gcd17")
        val n01 = bars("home_instant_nogcd_t01", lt = false, layout = lay, fire = true, take = "nogcd01")
        val n04 = bars("home_instant_nogcd_t04", lt = false, layout = lay, fire = true, take = "nogcd04")
        assertTrue(ringGold(ready, g01) > 150 && ringGold(ready, g12) > 150)
        assertFalse("the GCD over (1.5 s + the 0.2 s ease): back", differs(ready, g16))
        assertTrue(ringGold(ready, n01) > 150)
        assertFalse("no GCD: gone after none (0.36 s)", differs(ready, n04))
    }
    private fun all(b: Bitmap) = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())

    /**
     * ROUND 1533, THE FACE SAYS THE SPELL IT SHOWS (the user on test 1532, 2026-11-25: "When using hold to cast it will show the button glyph
     * as red if the original spell was not usable, I want that to also shift with the ability that its showing casting so its not
     * confusing"): while the hold's takeover has the A's face, its glyph is red only for the HOLD spell's range (the tap out of range: not
     * red; the hold out of range: red), the tap's proc halo is not round it and the badge is faded out - the takeover's picture does not
     * depend on the tap spell at all (its icon, range, halo); back after it ends
     */
    @Test fun theFaceSaysTheSpellItShows() {
        lang("en")
        val lay = CastView.Layout.DIAMONDS
        val tapOut = JSONObject().put("u", 1).put("m", 0).put("r", 0)
        val tapIn = JSONObject().put("u", 1).put("m", 0).put("r", 1)
        val ready = bars("home_glyph_tap_in_range", lt = false, layout = lay, states = "ok", tap = tapIn)
        val red = bars("home_glyph_tap_out_of_range", lt = false, layout = lay, states = "ok", tap = tapOut)
        val gbox = diffBox(ready, red) ?: error("the tap out of range changed nothing (no red glyph)")
        fun reds(b: Bitmap): Int { var n = 0; for (x in gbox.left until gbox.right) for (y in gbox.top until gbox.bottom) {
            val c = b.getPixel(x, y); if (Color.red(c) > 150 && Color.green(c) < 110 && Color.blue(c) < 110) n++ }; return n }
        assertTrue("the tap out of range: its glyph red (${reds(red)} red px)", reds(red) > 20 && reds(ready) < 3)
        // the takeover: the tap out of range, the hold (Lightning Shield) in range - the glyph NOT red
        val t1 = bars("home_glyph_hold_in_range_takeover", lt = false, layout = lay, fire = true, take = "cast", states = "ok", tap = tapOut)
        assertTrue("the hold in range has the face: its glyph not red (${reds(t1)} red px)", reds(t1) < 3)
        // the hold out of range, the tap in range: red while the takeover has the face
        val t2 = bars("home_glyph_hold_out_of_range_takeover", lt = false, layout = lay, fire = true, take = "cast", states = "range", tap = tapIn)
        assertTrue("the hold out of range has the face: its glyph red (${reds(t2)} red px)", reds(t2) > 20)
        // the takeover's picture says nothing of the tap: another tap icon, out of range, with a proc halo - the same picture (no halo round
        // the hold's face, no tap spell on the badge: it is faded out, no red glyph)
        val other = bars("home_glyph_takeover_other_tap_halo", lt = false, layout = lay, fire = true, take = "cast", states = "ok",
            tap = JSONObject().put("u", 1).put("m", 0).put("r", 0).put("gl", 1), tapIcon = 135990)
        assertFalse("the takeover's picture does not depend on the tap spell (icon, range, halo)", differs(t1, other))
        // without a takeover that tap is drawn (halo, icon, red glyph): the comparison above is not vacuous
        val otherIdle = bars("home_glyph_other_tap_halo_no_takeover", lt = false, layout = lay, states = "ok",
            tap = JSONObject().put("u", 1).put("m", 0).put("r", 0).put("gl", 1), tapIcon = 135990)
        assertTrue(differs(red, otherIdle))
        // the takeover over: the tap's red glyph again
        val back = bars("home_glyph_back_to_the_tap", lt = false, layout = lay, fire = true, take = "back", states = "ok", tap = tapOut)
        assertTrue("the takeover over: the tap's red glyph again (${reds(back)} red px)", reds(back) > 20)
    }

    @Test fun theBindBoxAndSettings() {
        lang("en")
        val o = SpellsOverlay(app, otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        o.onPadMap(PadMapModel.parse(padmap())!!)
        o.onCtl(SpellsModel.parseCtl(JSONObject("""{"type":"spellsctl","v":"b78","ctl":{"on":true,"mode":"bind","bind":8017}}"""))!!)
        val hh = 1080 - Strip.HEIGHT
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, hh)
        val withHint = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Theme.ink); o.draw(this) } }
        save(withHint, "b1_bindbox_hold_a_button_place_as_hold")
        o.onPadMap(PadMapModel.parse(padmap(holdOn = false))!!)
        val without = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Theme.ink); o.draw(this) } }
        save(without, "b2_bindbox_addon_says_off_no_hold_line")
        assertTrue("the foot's hold line shows while the add-on has hold to cast", differs(withHint, without))
        // fix/hold-tweaks (the user on test 1533: "I also cant see the current hold spells in each menu and it doesn't have a confirmation
        // on the hold spell"; "Instead I want select to cycle to the tap to cast"): the box drawn with the band's own drawing (as Home
        // wires it), each button's HOLD spell as its badge, Select's glyph at the bar's heading, a long press's Keep / Undo
        bindBoxHolds()
        // Settings > Controller: the Hold time picture, each choice
        for (t in CastSettings.HoldTime.values()) {
            CastSettings.holdTime = t
            val b = Bitmap.createBitmap(432, 216, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Theme.ink)
            ControllerPictures.draw(c, app, ControllerPictures.HOLD_TIME, RectF(0f, 0f, 432f, 216f), ControllerPictures.Look(t = 1.0f))
            save(b, "s1_settings_hold_time_${t.name.lowercase()}")
        }
        CastSettings.holdTime = CastSettings.HoldTime.NORMAL
    }

    /** fix/hold-tweaks: the Bind box with the band's drawing (CompanionHost's wiring): the holds' badges, Select's hint, the hold's
     *  Keep / Undo */
    private fun bindBoxHolds() {
        val cast = CastView(app) { }
        cast.layout = CastView.Layout.DIAMONDS
        cast.measure(View.MeasureSpec.makeMeasureSpec(1240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(354, View.MeasureSpec.EXACTLY))
        cast.layout(0, 0, 1240, 354)
        val icons = listOf(136048, 136026, 135990, 136052, 135813, 136015, 135824, 136075)
        fun layer() = JSONArray().apply { for (p in 1..8) put(JSONObject().put("p", p).put("k", "spell").put("id", 400 + p).put("i", icons[p - 1])) }
        val hud = JSONObject().put("hud", JSONObject().put("ok", true).put("page", 1).put("pages", JSONArray().put(true).put(true).put(true))
            .put("L", JSONObject().put("n", layer()).put("lt", layer()).put("rt", layer()).put("lr", layer())))
        cast.update(hud)
        for (i in 0 until 20) { Thread.sleep(25); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        cast.update(hud)
        CastStripModel.Live.set(CastStripModel.parse(JSONObject(ControllerSituationsShotsTest.BAR))!!)
        val o = SpellsOverlay(app, drawSet = { c, set, dpad, face, lit, icon, fight, hold, holdLit -> cast.drawSet(c, set, dpad, face, lit, icon, fight, hold, holdLit) },
            otherReader = { true })
        o.onBook(SpellsModel.parse(JSONObject(ControllerSituationsShotsTest.BOOK))!!)
        o.onPadMap(PadMapModel.parse(padmap())!!)
        val hh = 1080 - Strip.HEIGHT
        o.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY))
        o.layout(0, 0, w, hh)
        fun doc(extra: String, mode: String = "bind") = o.onCtl(SpellsModel.parseCtl(JSONObject(
            """{"type":"spellsctl","v":"b90","ctl":{"on":true,"mode":"$mode","bind":8017,"bsel":0$extra}}"""))!!)
        // (fix/hold-tweaks: the device ease - a frame, the clock on by [ease] ms, the frame saved)
        fun shot(name: String, ease: Long = 400L): Bitmap {
            val bm = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            Canvas(bm).apply { drawColor(Theme.ink); o.draw(this) }
            var left = ease
            while (left > 0L) {                                         // frames of 30 ms, as the screen draws them
                val st = minOf(30L, left); left -= st
                ShadowSystemClock.advanceBy(Duration.ofMillis(st))
                Canvas(bm).apply { drawColor(Theme.ink); o.draw(this) }
            }
            save(bm, name); return bm
        }
        doc("")
        val out = shot("b3_bindbox_out_of_combat_hold_badges_select_hint")
        // the same box without the holds: the badges are what changed
        o.onPadMap(PadMapModel.parse(padmapNoHolds())!!)
        val none = shot("b3n_bindbox_no_holds")
        o.onPadMap(PadMapModel.parse(padmap())!!)
        val diff = diffBox(none, out) ?: error("the hold badges drew nothing")
        assertTrue("the badges sit on the controller's picture (its middle third): $diff", diff.top > 300 && diff.bottom < 760)
        doc(""","bf":true""")
        shot("b4_bindbox_in_combat_holds_of_in_combat")
        doc(""","bcb":1""")
        val mid = shot("b5m_bindbox_select_easing_to_the_bar_90ms", ease = 90L)
        val onBar = shot("b5_bindbox_on_the_bar_select_above_the_controller")
        // the user: "when its selected move the glyph with the controller swap back above the controller and fade out slightly the one
        // thats not currently selected": on the bar, Select's glyph sits on the Controller heading's line, the controller faded
        doc("")
        val onCtl = shot("b3s_bindbox_controller_selected_select_on_the_bar")
        val selRow = { b: Bitmap, y: Int -> var n = 0; for (yy in y - 30 until y + 30) for (x in w - 420 until w - 80) { val c = b.getPixel(x, yy)
            if (Color.red(c) > 180 && Color.green(c) > 170 && Color.blue(c) > 140) n++ }; n }
        val ctlHead = 378; val barHead = 698
        assertTrue("controller selected: the glyph and word at the bar's heading", selRow(onCtl, barHead) > 150 && selRow(onCtl, ctlHead) < 40)
        assertTrue("bar selected: the glyph back above the controller", selRow(onBar, ctlHead) > 150 && selRow(onBar, barHead) < 40)
        assertTrue("easing: a frame between the two (the glyph on neither heading line yet)", differs(mid, onBar) && differs(mid, onCtl))
        val lum = { b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int -> var t = 0L; for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) { val c = b.getPixel(x, y); t += Color.red(c) + Color.green(c) + Color.blue(c) }; t }
        val ctlL = lum(onCtl, 300, 400, 940, 600); val ctlF = lum(onBar, 300, 400, 940, 600)
        assertTrue("the controller faded to about 60-70 % on the bar ($ctlF / $ctlL)", ctlF < ctlL * 0.75 && ctlF > ctlL * 0.5)
        // a long press placed the hold: the box asks Keep / Undo (Book b89), the answer's line says "Hold spell on"
        o.onBind(SpellsModel.parseBind(JSONObject().put("type", "spellsack").put("op", "bind").put("id", 8017).put("ok", true)
            .put("pad", JSONObject().put("pg", 1).put("b", 1).put("p", 8).put("hold", true)))!!)
        doc("", mode = "confirm")
        val conf = shot("b6_bindbox_hold_placed_keep_or_undo")
        // the confirm lights the HOLD BADGE, not the A face (pre-fix: 5442 px of the A face changed, a 104 px wide box)
        var changed = 0; var l = Int.MAX_VALUE; var rr = -1
        for (y in 400 until 600) for (x in 80 until 1160) {
            val p = out.getPixel(x, y); val q = conf.getPixel(x, y)
            if (maxOf(Math.abs(Color.red(p) - Color.red(q)), Math.abs(Color.green(p) - Color.green(q)), Math.abs(Color.blue(p) - Color.blue(q))) > 40) {
                changed++; l = minOf(l, x); rr = maxOf(rr, x)
            }
        }
        assertTrue("the hold confirm changes the badge only ($changed px, x $l..$rr)", changed in 50..2500 && rr - l < 75)
    }

    /** [padmap] with no hold anywhere */
    private fun padmapNoHolds(): JSONObject {
        val m = padmap()
        val sets = m.getJSONObject("m").getJSONArray("sets")
        for (i in 0 until sets.length()) { val sl = sets.getJSONObject(i).getJSONArray("slots"); for (j in 0 until sl.length()) { sl.getJSONObject(j).remove("h"); sl.getJSONObject(j).remove("hc") } }
        return m
    }
}
