package com.jrees.wowcompanion

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE 1515 MERGE INTO MOVABLE HOME (test build 1515's sticky realm key; the user's rulings 2026-11-20: WoW Forever has no dedicated
 * realms, a character's whole name is unique and is its identity, its key is the realm its settings already live under; the add-on asks
 * ONCE when one name holds settings under 2+ realms). Home's per-character record follows ONLY the add-on's resolved key - the padmap's
 * who.n + who.r through [CharKey.of], exactly as CompanionHost feeds [HomeLayoutStore.character]:
 *  - while the add-on ASKS (no r, the question's candidates in pk) Home is pending: nothing per character read or written;
 *  - a held edit (Settings' facades, or edit mode's draft opened while pending) lands on the PICKED key with its changed fields only,
 *    the other realm's record untouched; "New character" puts it on the new key only;
 *  - Home's edit mode and the key question never both hold the controller ([KeyPick.decide]'s editing, [KeyPick.editMayOpen]);
 *  - the reset coverage keeps Home's keys (homeLayout.<who>, the device's homeLayoutDefault / homeLayoutSlots with All characters only,
 *    the add-on's rbm / ctop) and never resets the add-on's who records.
 * Names and realms are made up.
 */
class HomeKeyPickTest {
    @After fun down() { HomeSettings.startForTest(null); RealmFix.forget() }

    private val r7 = "Realm 7"
    private val r8 = "Realm 8"
    private val k7 = "Korvash-$r7"
    private val k8 = "Korvash-$r8"

    private fun fresh(): HomeLayoutStoreTest.Prefs { val p = HomeLayoutStoreTest.Prefs(); HomeSettings.startForTest(p); return p }

    private fun dpad(who: String) = PadMapModel.parse(JSONObject(
        """{"type":"padmap","v":"m183","m":{"page":1,"sets":[],"dpad":{"on":true,"want":true,"cols":{"on":1,"off":2},"who":$who}}}"""))!!.dpad!!

    private val asking = """{"n":"Korvash","rs":"ask","rr":"empty","rh":"none","nc":2,"uc":0,"gs":"new",
        "pk":[{"r":"$r7","n":4},{"r":"$r8","n":5}]}"""
    private fun resolved(r: String, rs: String) = """{"n":"Korvash","r":"$r","rs":"$rs","rr":"empty","rh":"none","nc":2,"uc":0,"gs":"new"}"""

    /** what CompanionHost does with a current padmap's dpad (the 1514 / 1515 merges): the WHOLE key, else pending by name */
    private fun feed(d: PadMapModel.Dpad) {
        val ck = CharKey.of(d.name, d.realm)
        if (ck != null || !d.name.isNullOrEmpty()) HomeLayoutStore.character(ck, d.name)
    }

    /** two realms of one name, each with its own record (the shape the add-on asks about) */
    private fun twoRecords(p: HomeLayoutStoreTest.Prefs) {
        HomeLayoutStore.character(k7)
        HomeLayoutStore.set(HomeLayout(chatSide = HomeLayout.Side.RIGHT, rest = HomeRest.ROW))
        HomeLayoutStore.character(k8)
        HomeLayoutStore.set(HomeLayout(card = HomeLayout.CardDock.OFF, cast = HomeLayout.CastDock.ACROSS))
        HomeLayoutStore.character(null)
        assertTrue(p.m.containsKey("homeLayout.$k7") && p.m.containsKey("homeLayout.$k8"))
    }

    private fun rec(p: HomeLayoutStoreTest.Prefs, k: String) = HomeLayoutJson.parse(p.m["homeLayout.$k"] as String)

    @Test fun whileTheAddOnAsksHomeReadsAndWritesNothingPerCharacter() {
        val p = fresh()
        twoRecords(p)
        val before = HashMap(p.m)
        val d = dpad(asking)
        assertNull("asking: no realm, no key", d.realm); assertNull(CharKey.of(d.name, d.realm))
        assertEquals(listOf(r7, r8), d.picks.map { it.realm })
        feed(d)
        assertTrue(HomeLayoutStore.pending); assertNull(HomeLayoutStore.who)
        assertEquals("Home shows the default (no candidate's record read)", HomeLayoutStore.default(), HomeSettings.layout)
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        HomeSettings.rest = HomeRest.ROW
        assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        for (k in listOf("homeLayout.$k7", "homeLayout.$k8", HomeLayoutStore.KEY_DEFAULT))
            assertEquals("$k is not written while asking", before[k], p.m[k])
        assertTrue("no partial key", p.m.keys.filter { it.startsWith(HomeLayoutStore.KEY_PREFIX) }.all { CharKey.isKey(it.removePrefix(HomeLayoutStore.KEY_PREFIX)) })
        assertEquals("the owner is the name alone", HomeLayoutStore.Owner(null, "Korvash"), HomeLayoutStore.owner())
    }

    @Test fun aPendingEditResolvedByAPickLandsOnThePickedKeyChangedFieldsOnly() {
        for ((picked, other) in listOf(k8 to k7, k7 to k8)) {
            val p = fresh()
            twoRecords(p)
            val otherBefore = p.m["homeLayout.$other"]
            val pickedBefore = rec(p, picked)
            feed(dpad(asking))
            // the held edit: a facade's change (Settings > Bottom screen) while the question is up
            HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
            // the player picks: the add-on locks that key and its next padmap carries who.r = the picked realm (rs "picked")
            feed(dpad(resolved(CharKey.nameOf(picked)!!.let { picked.removePrefix("$it-") }, "picked")))
            assertFalse(HomeLayoutStore.pending); assertEquals(picked, HomeLayoutStore.who)
            val now = rec(p, picked)
            assertEquals("$picked: the session's change", HomeLayout.Share.THIRD, now.chatShare)
            assertEquals("$picked: its own fields kept", pickedBefore.copy(chatShare = HomeLayout.Share.THIRD, name = now.name), now)
            assertEquals("$other: untouched", otherBefore, p.m["homeLayout.$other"])
            down()
        }
    }

    @Test fun anEditModeDraftOpenedWhileAskingIsSavedOntoThePickedKey() {
        val p = fresh()
        twoRecords(p)
        val k7Before = p.m["homeLayout.$k7"]
        feed(dpad(asking))
        val opened = HomeLayoutStore.owner()                                  // edit mode opened (then parked) on the name alone
        val base = HomeSettings.layout
        val draft = base.copy(xpBar = !base.xpBar)
        var heard = 0
        HomeLayoutStore.onOwner { heard++ }
        feed(dpad(resolved(r8, "picked")))                                    // the pick while edit mode was parked
        assertTrue("the owner change is heard (edit mode re-checks)", heard > 0)
        assertEquals("the same name's resolved key: Save lays the session's change on", HomeLayoutStore.EditSave.LAY_ON, HomeLayoutStore.editSaveFor(opened))
        HomeSettings.saveLayout(HomeLayoutStore.editSave(opened, base, draft)!!)
        val k = rec(p, k8)
        assertEquals(draft.xpBar, k.xpBar)
        assertEquals("Realm 8's own card kept", HomeLayout.CardDock.OFF, k.card); assertEquals(HomeLayout.CastDock.ACROSS, k.cast)
        assertEquals("Realm 7 untouched", k7Before, p.m["homeLayout.$k7"])
        // another name's key is never this draft's
        assertEquals(HomeLayoutStore.EditSave.NONE, HomeLayoutStore.editSaveFor(opened, HomeLayoutStore.Owner("Thrandil-$r8", "Thrandil")))
    }

    @Test fun newCharacterPutsTheHeldEditOnTheNewKeyOnly() {
        val p = fresh()
        twoRecords(p)
        val k7Before = p.m["homeLayout.$k7"]; val k8Before = p.m["homeLayout.$k8"]
        feed(dpad(asking))
        HomeLayoutStore.update { it.copy(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.TWO_THIRDS) }
        // "New character" (Book b74 code 9): the add-on's fresh key ("Korvash-Forever" when no realm is reported), rs "new"
        feed(dpad(resolved("Forever", "new")))
        assertEquals("Korvash-Forever", HomeLayoutStore.who)
        val n = rec(p, "Korvash-Forever")
        assertEquals(HomeLayout.Side.RIGHT, n.chatSide); assertEquals(HomeLayout.Share.TWO_THIRDS, n.chatShare)
        assertEquals("seeded from the default otherwise", HomeLayoutStore.default().rest, n.rest)
        assertEquals("Realm 7 untouched", k7Before, p.m["homeLayout.$k7"])
        assertEquals("Realm 8 untouched", k8Before, p.m["homeLayout.$k8"])
    }

    @Test fun aHeldEditNeverGoesToAnotherNameOrALaterLogin() {
        val p = fresh()
        twoRecords(p)
        feed(dpad(asking))
        HomeLayoutStore.update { it.copy(card = HomeLayout.CardDock.ABOVE_CHAT) }
        // a relog to another character while asking: the held edit is Korvash's only
        feed(dpad("""{"n":"Thrandil","r":"$r7","rs":"guid"}"""))
        assertEquals("Thrandil-$r7", HomeLayoutStore.who)
        assertTrue(HomeSettings.layout.card != HomeLayout.CardDock.ABOVE_CHAT)
        // Korvash again, resolved: the edit was let go with the switch (never laid on later)
        feed(dpad(resolved(r7, "guid")))
        assertEquals(HomeLayout.CardDock.ON_MAP, rec(p, k7).card)
        assertTrue(HomeLayoutStore.carryOk(k7, "Korvash")); assertFalse(HomeLayoutStore.carryOk(k7, "Thrandil"))
        assertFalse("a partial key never", HomeLayoutStore.carryOk("Korvash-", "Korvash")); assertFalse(HomeLayoutStore.carryOk(k7, null))
    }

    // ---------------------------------------------------------------- one owner of the controller

    @Test fun editModeAndTheKeyQuestionNeverBothHoldTheController() {
        val cands = listOf(KeyPick.Cand(r7, 4, 1), KeyPick.Cand(r8, 5, 2))
        // a small model of the host: the question (open = shown or waiting for the controller) and edit mode (open / parked)
        var askOpen = false
        var edit = 0                                                           // 0 none, 1 open (holds the controller), 2 parked
        fun tryEdit(): Boolean { if (edit != 0) return true; if (!KeyPick.editMayOpen(askOpen)) return false; edit = 1; return true }
        fun ask(fight: Boolean = false) {
            when (KeyPick.decide(cands, askOpen, false, null, ready = true, fight = fight, editing = edit != 0)) {
                KeyPick.Act.ASK -> askOpen = true
                KeyPick.Act.CLOSE -> askOpen = false
                else -> {}
            }
        }
        fun both() = askOpen && edit != 0
        // edit mode first: the question waits while it is open and while it is parked, and comes once it has ended
        assertTrue(tryEdit()); ask(); assertFalse(both()); assertFalse(askOpen)
        assertEquals(KeyPick.Act.WAIT, KeyPick.decide(cands, false, false, null, ready = true, fight = false, editing = true))
        edit = 2; ask(); assertFalse("parked (a fight, a room): still waits", askOpen)
        edit = 0; ask(); assertTrue("edit mode ended: asked", askOpen)
        // the question first: edit mode never opens (every way in is the same gate)
        assertFalse(tryEdit()); assertFalse(both())
        assertFalse(KeyPick.editMayOpen(true)); assertTrue(KeyPick.editMayOpen(false))
        // should edit mode be there anyway, the open question is put away (comes back after)
        assertEquals(KeyPick.Act.CLOSE, KeyPick.decide(cands, true, false, null, ready = true, fight = false, editing = true))
        // answered: edit mode may open again
        askOpen = false; assertTrue(tryEdit()); ask(); assertFalse(both())
        // every combination the host can reach keeps one owner
        for (open in listOf(false, true)) for (ed in listOf(false, true)) for (fight in listOf(false, true)) {
            val a = KeyPick.decide(cands, open, false, null, ready = true, fight = fight, editing = ed)
            if (ed) assertTrue("open=$open fight=$fight: never ASK while edit mode is there", a != KeyPick.Act.ASK)
            if (ed && open) assertEquals(KeyPick.Act.CLOSE, a)
        }
    }

    @Test fun theHostWiresBothGates() {
        val src = listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion"), java.io.File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val host = java.io.File(src, "CompanionHost.kt").readText()
        val home = java.io.File(src, "HomeView.kt").readText()
        assertTrue(host.contains("homeView.editBlocked = { !KeyPick.editMayOpen(keyAskOpen) }"))
        assertTrue(host.contains("val editing = homeView.editPresent") &&
            host.contains("KeyPick.decide(cands, keyAskOpen, later, sentAgo, ready, fight, changed, editing)"))
        assertTrue(home.contains("val editPresent: Boolean get() = edit != null"))
        val open = home.substring(home.indexOf("fun editOpen("), home.indexOf("val saved = HomeSettings.layout", home.indexOf("fun editOpen(")))
        assertTrue("edit mode's one way in checks the gate", open.contains("if (editBlocked?.invoke() == true)"))
        // Home's store is fed only the add-on's whole key (CharKey.of(n, r): no r while asking)
        assertTrue(host.contains("val ck = CharKey.of(d.name, d.realm)") && host.contains("if (current && named) HomeLayoutStore.character(ck, d.name)"))
    }

    // ---------------------------------------------------------------- the reset coverage

    @Test fun resetKeepsHomesKeysAndNeverTheAddOnsWhoRecords() {
        val L = setOf(ResetPlan.Kind.LAYOUTS)
        val keys = setOf("homeLayout.$k7", "homeLayout.$k8", "homeLayout.Thrandil-$r7", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS,
            HomeLayoutStore.KEY_MIGRATED)
        assertEquals("This character: its own record only", setOf("homeLayout.$k8"), ResetPlan.prefKeys("home", keys, L, k8, false))
        assertEquals("All characters: every record, the default and the slots",
            setOf("homeLayout.$k7", "homeLayout.$k8", "homeLayout.Thrandil-$r7", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS),
            ResetPlan.prefKeys("home", keys, L, k8, true))
        assertTrue("asking (no key): This character takes nothing of Home's", ResetPlan.prefKeys("home", keys, L, null, false).isEmpty())
        for (top in listOf("rbm", "ctop")) {
            val k = ResetPlan.SV_KEYS.first { it.top == top }
            assertEquals("WowCompanionSpellsDB", k.variable); assertEquals(ResetPlan.Kind.LAYOUTS, k.kind); assertTrue(k.perChar)
        }
        assertTrue("never the add-on's GUID records", ResetPlan.SV_NEVER.getValue("WowCompanionDB").contains("who"))
        assertTrue(ResetPlan.SV_KEYS.none { it.top == "who" })
        // a whole reset with every kind: rbm / ctop for the picked key only, never who
        val tables = mapOf(
            "WowCompanionSpellsDB" to listOf(mapOf<Any, Any>("rbm" to mapOf(k7 to 1, k8 to 0), "ctop" to mapOf(k7 to 1, k8 to 1))),
            "WowCompanionDB" to listOf(mapOf<Any, Any>("who" to mapOf("Player-1-00000001" to mapOf("k" to k8)))))
        val t = ResetPlan.svTargets(ResetPlan.Kind.values().toSet(), k8, false, tables)
        assertEquals(setOf("rbm" to k8, "ctop" to k8), t.filter { it.top == "rbm" || it.top == "ctop" }.map { it.top to it.who }.toSet())
        assertTrue(t.none { it.top == "who" })
        val all = ResetPlan.svTargets(ResetPlan.Kind.values().toSet(), k8, true, tables)
        assertEquals(setOf(k7, k8), all.filter { it.top == "rbm" }.map { it.who }.toSet())
        assertTrue(all.none { it.top == "who" })
    }
}
