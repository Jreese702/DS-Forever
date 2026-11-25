package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOLD TO CAST, WHAT THE APP SHOWS (PadMap m190 / PadTabCtl p59 / Book b78; the user's pick "A: corner badge"): the padmap's per-place
 * hold (`h`) and `hold.on`, the hold spells' cooldowns (`holdcd`) by Home's band key, the room's hold chooser, the Bind box's hold answer,
 * the badge's outer side, Settings' Hold time tile, es / fr, and the drawing hooks (Home's band, the room's Hold block and badges, the
 * Bind box's foot).
 */
class HoldCastUiTest {
    private val src = listOf("../companion/app/src/main/java/com/jrees/wowcompanion", "companion/app/src/main/java/com/jrees/wowcompanion")
        .map { File(it) }.first { it.isDirectory }
    private fun kt(n: String) = File(src, n).readText()
    private val i18n = File(src, "../../../../assets/companion/i18n")

    private fun padmap(holdOn: Boolean? = true): PadMapModel.Map {
        val sets = JSONArray()
        for (set in 1..4) {
            val a = JSONArray()
            for (p in 1..8) {
                val o = JSONObject().put("pos", p).put("kind", if (set == 1 && p >= 5) "fixed" else "spell").put("s", (set - 1) * 12 + p)
                    .put("n", "S$set$p").put("id", 100 + p).put("i", 1000 + p)
                if (set == 2 && p == 6) o.put("h", JSONObject().put("n", "Chain Heal").put("id", 1064).put("i", 136042))
                if (set == 1 && p == 8) o.put("h", JSONObject().put("n", "Hearthstone").put("t", "item").put("key", "i:6948").put("i", 134414))
                if (set == 3 && p == 1) o.put("hc", JSONObject().put("n", "Frost Shock").put("id", 8056).put("i", 135849))
                a.put(o)
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = JSONObject().put("page", 1).put("pages", 3).put("sets", sets)
        if (holdOn != null) m.put("hold", JSONObject().put("on", holdOn))
        return PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m194").put("m", m))!!
    }

    @Test fun thePadmapCarriesEachPlacesHold() {
        val m = padmap()
        assertEquals(true, m.holdOn)
        val h = PadMapModel.holdAt(m, 2, 6)!!
        assertEquals("Chain Heal", h.name); assertEquals(1064, h.id); assertEquals(136042, h.icon)
        val a = PadMapModel.holdAt(m, 1, 8)!!
        assertEquals("item", a.layer); assertEquals("i:6948", a.key); assertNull("an item is no spell", a.spellId)
        assertNull(PadMapModel.holdAt(m, 1, 6))
        assertEquals("Home's band keys (layer * 8 + position - 1)", setOf(1 * 8 + 5, 0 * 8 + 7), PadMapModel.holdRefs(m).keys)
        // PER TAB (the user on test 1528: "the hold buttons are showing on my out of combat bar? It should be seperate")
        assertEquals("In combat's holds are their own", setOf(2 * 8 + 0), PadMapModel.holdRefs(m, combat = true).keys)
        assertEquals("Frost Shock", PadMapModel.slot(m, 3, 1)!!.holdOf(true)!!.name)
        assertNull("Out of combat's LT + Y is not In combat's", PadMapModel.slot(m, 2, 6)!!.holdOf(true))
        assertNull("an add-on before m190 says nothing", padmap(null).holdOn)
    }

    /** the 1532 audit: B (position 7) never carries a hold in the app's drawing either - a padmap that says one (an older or a broken
     *  add-on) puts no badge, ring or takeover on B */
    @Test fun bNeverCarriesAHoldInTheDrawing() {
        val sets = org.json.JSONArray()
        for (set in 1..4) {
            val a = org.json.JSONArray()
            for (p in 1..8) {
                val o = JSONObject().put("pos", p).put("kind", "spell").put("s", (set - 1) * 12 + p)
                if (set == 1 && (p == 7 || p == 8)) o.put("h", JSONObject().put("n", "Chain Heal").put("id", 1064).put("i", 136042))
                a.put(o)
            }
            sets.put(JSONObject().put("set", set).put("slots", a))
        }
        val m = PadMapModel.parse(JSONObject().put("type", "padmap").put("v", "m194").put("m", JSONObject().put("page", 1).put("pages", 3)
            .put("sets", sets).put("hold", JSONObject().put("on", true))))!!
        assertEquals("A's hold kept, B's never", setOf(0 * 8 + 7), PadMapModel.holdRefs(m).keys)
        val page = File(listOf(File("../app"), File("app")).first { it.isDirectory }.parentFile!!.absoluteFile,
            "companion/app/src/main/java/com/jrees/wowcompanion/PadTabPage.kt").readText()
        assertTrue("the Controller room's badges skip B", page.contains("for (p in 1..8) if (p != 7) PadMapModel.slot(m, s, p)?.holdOf("))
    }

    @Test fun aHoldOnlyOnAButtonThatHasSomething() {
        // the user, 2026-11-24: "You should only be able to bind a hold on a button that has something already"
        val m = padmap()
        assertTrue("a spell", PadMapModel.holdable(PadMapModel.slot(m, 2, 6)))
        assertTrue("a base face: its game action (a tap does it)", PadMapModel.holdable(PadMapModel.slot(m, 1, 8)))
        val empty = PadMapModel.Slot(3, 3, PadMapModel.Kind.NONE, null, null, 0)
        assertFalse("never B (the user: \"remove the hold option on B\")", PadMapModel.holdable(PadMapModel.Slot(7, 19, PadMapModel.Kind.SPELL, 1, "x", 1)))
        assertFalse("an empty button", PadMapModel.holdable(empty))
        // m194: a game binding on the button is the game's own command - the add-on's router never hears that press
        assertFalse("never a game binding", PadMapModel.holdable(PadMapModel.Slot(5, 17, PadMapModel.Kind.BIND, null, "Jump", 0)))
        assertFalse(PadMapModel.holdable(null))
        val room = kt("PadTabPage.kt")
        assertTrue("no Bind hold button on an empty one", room.contains("&& PadMapModel.holdable(slotOf(m, shown(), pos))"))
        assertTrue("no Hold row on an empty one", room.contains("if (holdOn(m) && PadMapModel.holdable(sl)) {"))
    }

    @Test fun theHoldCooldowns() {
        val cd = PadMapModel.parseHoldCd(JSONObject().put("type", "holdcd").put("k", JSONObject()
            .put("lt:6", JSONObject().put("d", 8).put("l", 5.5))
            .put("bare:8", JSONObject().put("d", 30).put("l", 2).put("e", true))
            .put("odd:3", JSONObject().put("d", 3).put("l", 1))
            .put("rt:2", JSONObject().put("d", 3).put("l", 4))))!!
        assertEquals(setOf(HoldCast.key(1, 6), HoldCast.key(0, 8)), cd.keys)
        assertEquals(5.5, cd.getValue(HoldCast.key(1, 6)).left, 0.0)
        assertTrue(cd.getValue(HoldCast.key(0, 8)).estimated)
        assertEquals(emptyMap<Int, PadMapModel.FaceCd>(), PadMapModel.parseHoldCd(JSONObject().put("type", "holdcd")))
        assertNull(PadMapModel.parseHoldCd(JSONObject().put("type", "facecd")))
    }

    @Test fun theRoomsHoldChooser() {
        fun ctl(hold: Boolean?) = PadMapModel.parseCtl(JSONObject().put("type", "padtabctl").put("v", "p59").put("c", JSONObject()
            .put("on", true).put("view", "normal").put("focus", "buttons").put("pos", 6).put("set", 2).put("shown", 2)
            .put("chooser", JSONObject().put("list", "spells").put("row", 1).put("id", "1064").apply { if (hold != null) put("hold", hold) })))!!
        assertTrue(ctl(true).chooser!!.hold)
        assertFalse(ctl(null).chooser!!.hold)
    }

    @Test fun theBindBoxsHoldAnswer() {
        val r = SpellsModel.parseBind(JSONObject().put("type", "spellsack").put("op", "bind").put("id", 403).put("ok", true)
            .put("pad", JSONObject().put("pg", 1).put("b", 2).put("p", 5).put("hold", true)))!!
        assertTrue(r.ok); assertTrue(r.hold)
        assertEquals("Hold spell on", SpellsOverlay.answerWord(r))
        val plain = SpellsModel.parseBind(JSONObject().put("type", "spellsack").put("op", "bind").put("id", 403).put("ok", true)
            .put("pad", JSONObject().put("s", 17).put("pg", 1).put("b", 2).put("p", 5)))!!
        assertFalse(plain.hold)
        assertEquals("Bound to", SpellsOverlay.answerWord(plain))
    }

    @Test fun theBadgeSitsOnTheClustersOuterSide() {
        // drawn 0..3 the D-pad left / up / right / down, 4..7 the faces left / top / right / bottom (y down)
        assertEquals(-1f to 1f, HoldCast.badgeSide(0)); assertEquals(-1f to -1f, HoldCast.badgeSide(1))
        assertEquals(1f to 1f, HoldCast.badgeSide(2)); assertEquals(-1f to 1f, HoldCast.badgeSide(3))
        assertEquals(-1f to 1f, HoldCast.badgeSide(4)); assertEquals(-1f to -1f, HoldCast.badgeSide(5))
        assertEquals(1f to 1f, HoldCast.badgeSide(6)); assertEquals(-1f to 1f, HoldCast.badgeSide(7))
        assertFalse("m195: no 150 ms flash any more (the takeover)", kt("HoldArt.kt").contains("FLASH_MS"))
    }

    @Test fun settingsHoldTimeTile() {
        assertEquals(listOf("Short", "Normal", "Long"), CastSettings.HoldTime.values().map { it.label })
        assertEquals("m194: the add-on's looks", listOf(2, 3, 4), CastSettings.HoldTime.values().map { it.ticks })
        assertTrue(SettingsTabs.Block.CONTROLLER.rows.contains("Hold time"))
        val t = kt("ControllerTiles.kt")
        assertTrue(t.contains("Slot(ControllerPictures.HOLD_TIME)"))
        assertTrue(t.contains("ControllerPictures.HOLD_TIME -> HOLDS.getOrNull(k)?.let { if (CastSettings.holdTime != it) { CastSettings.holdTime = it; HoldCast.holdTimeChanged() } }"))
        assertTrue(kt("ControllerPictures.kt").contains("HOLD_TIME -> holdTime(c, look)"))
    }

    @Test fun theDrawingHooks() {
        val cast = kt("CastView.kt")
        assertTrue("Home's band draws the hold after each button", cast.contains("next = min(next, drawHold(c, pos, src, li, circle, b, now))"))
        assertFalse("m195: no arc on the big button (the charge ring is the badge's)", cast.contains("HoldArt.progress("))
        assertTrue("m195: the charge ring round the badge", cast.contains("HoldArt.chargeRing(c, bx, by, r, ch, charged)"))
        assertTrue("round 1533: the badge's state is ONE shade of its icon (HoldArt.stateFilter: grey, red range, blue mana), no red ring",
            cast.contains("HoldArt.badge(c, bx, by, r, icon, sw, lit = ch >= 0f, filter = hst?.let { HoldArt.stateFilter(it.u, it.m, it.r) })"))
        assertTrue("m195: the takeover before the badge", cast.contains("next = min(next, drawTakeover(c, pos, src, li, circle, now))"))
        assertTrue("the band's own pad reading drives the ring", cast.contains("holdWatch(mask, down, wheel)"))
        val home = kt("HomeView.kt")
        assertTrue("PER TAB: the badges are the holds that would fire now (none while Party mode is on: the 1530 audit)",
            home.contains("castView.holdRefs = PadMapModel.holdRefs(map, combat = fightNow && map.faceCc != false).takeIf { map.holdOn != false && !partyOnHere() }")
                && home.contains("fightNow = fight; applyDpad(); holdApply()"))
        // (the 1528 merge of feat/exit-now's view release: held weakly - HoldCast is an object, the Home it drew for is let go)
        assertTrue(home.contains("HoldCast.onChange = weakly(this) { v -> v.castView.postInvalidateOnAnimation() }"))
        val room = kt("PadTabPage.kt")
        // the user's follow-up (2026-11-24): the pane = Press + Hold only, no In combat / Otherwise pair; Y Hold spell a real button
        assertTrue(room.contains("if (holdHere(m) && talkKind() == TALK_NONE) { drawHoldPane(c, m, s, slc, w, h); return }"))
        val pane = room.substringAfter("private fun drawHoldPane(").substringBefore("\n    }\n")
        assertFalse("no Otherwise row in the pane", pane.contains("\"Otherwise\""))
        assertFalse("no over-macro line in the pane (the grid's mark says it)", pane.contains("overMacro"))
        assertTrue(pane.contains("Tr.t(\"Press\")") && pane.contains("Tr.t(\"Hold\")"))
        // the user, 2026-11-24: "Use the word bind instead of change"; "the bind hold should be under the move"
        assertTrue("Y Bind hold: the kit's button", pane.contains("UiKit.button(c, holdRect(m, w, h), HOLD_BIND, glyph(6)"))
        assertTrue("A Bind", pane.contains("if (fightTab) \"Bind combat spell\" else \"Bind\", glyph(8)") && !pane.contains("\"Change"))
        assertTrue("the order: X Move above Y Bind hold above A Bind (the hold in Move's own place, Move one step up)",
            room.contains("private fun holdRect(m: PadMapModel.Map, w: Float, h: Float): RectF = moveRect(w, h)")
                && pane.contains("UiKit.button(c, moveAt(w, h), \"Move\""))
        assertTrue("its touch: Book b83's page-3 code 28 (b80 on the 1528b and 1529 merges: 26 / 27 are feat/top-castbar's swing off / on)",
            room.contains("tap(PadMapModel.holdCells())") && PadMapModel.holdCells().last() == (PadMapModel.ROW_PADTAB to 28))
        assertEquals("the hold chooser's code is its own", 28..28, PadMapModel.PAGE3_CODES["hold chooser"])
        assertEquals("the swing keeps 26 / 27", 26..27, PadMapModel.PAGE3_CODES["top bar swing"])
        assertTrue("the controller's Y pushes it", room.contains("return DOWN_HOLD   // m190: Y Hold spell, pushed at the press"))
        assertTrue(room.contains("HoldArt.badge(c, r.left + 12f * k, r.top + 12f * k, 15f * k, Icons.get(hr.icon), -1f, lit = false)"))
        assertTrue("the chooser's Clear: the tab's own hold", room.contains("if (holdChooser()) return if (sl?.holdOf(fight()) != null) Thing.Unhold else null"))
        assertTrue("the room shows each tab's own Hold row", room.contains("val hr = sl?.holdOf(fightTab)"))
        assertTrue(kt("SpellsOverlay.kt").contains("holdHint(c, box.left + 80f, HINT_Y)"))
        val host = kt("CompanionHost.kt")
        assertTrue(host.contains("\"holdmap\" -> if (current) HoldCast.onMap(msg)") && !host.contains("holdprobe") && host.contains("HoldCast.onFacecast(msg)")
            && host.contains("\"holdcd\" -> if (current) PadMapModel.parseHoldCd(msg)?.let { homeView.onHoldCd(it) }")
            && host.contains("\"holdcast\" -> if (current) HoldCast.onHoldCast(msg)"))
        assertTrue("the press log knows the hold way", PressLog.PATHS.contains("slothold"))
    }

    /** 19: round 1533 (the user: "The red outline is an eyesore we should just shade the ability like we do with the glyphs"): one shade
     *  of the badge's icon - grey when not usable for another reason than power, else red out of range, else blue for power */
    @Test fun theBadgesShadeIsOneOfGreyRedBlue() {
        assertEquals(HoldCast.Shade.UNUSABLE, HoldCast.badgeShade(0, 0, 1))
        assertEquals(HoldCast.Shade.RANGE, HoldCast.badgeShade(1, 0, 0))
        assertEquals(HoldCast.Shade.MANA, HoldCast.badgeShade(0, 1, 1))
        assertEquals("power + range: red (red is range)", HoldCast.Shade.RANGE, HoldCast.badgeShade(0, 1, 0))
        assertEquals("unusable + range: grey", HoldCast.Shade.UNUSABLE, HoldCast.badgeShade(0, 0, 0))
        assertEquals(HoldCast.Shade.NONE, HoldCast.badgeShade(1, 0, 1))
        assertEquals("range unknown (-1): no red", HoldCast.Shade.NONE, HoldCast.badgeShade(1, 0, -1))
        val art = kt("HoldArt.kt")
        assertTrue("stateFilter maps the shade to CastView's own grey / blue and the range red",
            art.contains("HoldCast.Shade.UNUSABLE -> cfUnusable") && art.contains("HoldCast.Shade.RANGE -> cfRange") && art.contains("HoldCast.Shade.MANA -> cfMana")
                && art.contains("val cfUnusable = android.graphics.LightingColorFilter(0x666666, 0)") && art.contains("val cfMana = android.graphics.LightingColorFilter(0x8080FF, 0)")
                && art.contains("val cfRange = android.graphics.LightingColorFilter(0xFF524C, 0)"))
    }

    /** 20: round 1533's drawing pins: no red ring, the takeover drawn CHECKED (the game's gold ring) with no ring of our own, the
     *  charge ring's glow under its fill */
    @Test fun theNewLookIsWired() {
        val art = kt("HoldArt.kt")
        assertFalse("no red ring on the badge", art.contains("RANGE_RED") || art.contains("outOfRange"))
        assertFalse("no takeover ring of our own", art.contains("fun fired("))
        val ring = art.substringAfter("fun chargeRing(").substringBefore("\n    }\n")
        assertTrue("the charge ring GLOWS: a blurred gold arc under the fill, then the fill, a bright head",
            ring.indexOf("pGlow.maskFilter = blur(") in 0 until ring.indexOf("pStroke.color = CHARGE; pStroke.strokeWidth = w")
                && ring.contains("Color.argb((90 + 130 * f).toInt(), 255, 200, 80)") && ring.contains("CHARGE_HEAD"))
        val cast = kt("CastView.kt")
        val take = cast.substringAfter("private fun drawTakeover(").substringBefore("\n    }\n")
        assertTrue("the takeover's face drawn checked (the game's gold ring, 4 units beyond)", take.contains("takeSt.copyFrom(hst); takeSt.chk = 1")
            && take.contains("drawSlot(c, pos, 0f, 0f, circle, tb, takeSt, -1, now, false, 0f, null, sweep)"))
        assertFalse("no HoldArt.fired", take.contains("HoldArt.fired"))
        assertTrue("D3: a fired hold draws as the game has it (up)", cast.contains("((held and (1 shl src)) != 0 && !HoldCast.forced(src + 1))"))
        assertEquals("the takeover and the badge still drawn per button", true,
            cast.contains("next = min(next, drawTakeover(c, pos, src, li, circle, now))") && cast.contains("HoldArt.chargeRing(c, bx, by, r, ch, charged)"))
        // the hint: the fire point and that it casts when full (es / fr)
        assertEquals("%s, casts when full", PadTabPage.HOLD_POINT)
        assertEquals("casts when full", ControllerPictures.LET_GO)
        for ((l, a, b) in listOf(Triple("es", "se lanza al llenarse", "%s, se lanza al llenarse"), Triple("fr", "se lance une fois plein", "%s, se lance une fois plein"))) {
            val o = JSONObject(File(i18n, "$l.json").readText())
            assertEquals(a, o.getJSONObject("plain").getString("casts when full"))
            assertEquals(b, o.getJSONObject("templates").getString("%s, casts when full"))
            assertFalse("$l: the old words gone", File(i18n, "$l.json").readText().contains("then let go"))
        }
    }

    @Test fun spanishAndFrench() {
        val words = listOf(PadTabPage.HOLD_BIND, "Bind", "Bind combat spell", ControllerPictures.LET_GO, "Clear hold spell", "Hold spell on",
            SpellsOverlay.HOLD_HINT, "Hold time", "Normal", "Short", "Long", "Hold", "hold", "Press",
            "Fight buttons unavailable: the game is not taking key changes right now")
        for (l in listOf("es", "fr")) {
            val o = JSONObject(File(i18n, "$l.json").readText())
            val plain = o.getJSONObject("plain")
            for (w in words) assertTrue("$l: $w", plain.has(w))
            assertTrue("$l: hold %s", o.getJSONObject("templates").has("hold %s"))
            assertTrue("$l: ${PadTabPage.HOLD_POINT}", o.getJSONObject("templates").has(PadTabPage.HOLD_POINT))
            assertFalse("$l: no probe line any more", plain.has("Hold to cast isn't available yet"))
            assertTrue("$l keeps CRLF", File(i18n, "$l.json").readText().contains("\r\n"))
        }
        Tr.load("es", File(i18n, "es.json").readText())
        assertNotNull(Tr)
    }
}
