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
 *  - the merge's review: the held edit is kept on disk by name (homeLayoutHeld) - after "Later" it survives an app restart and a switch
 *    to another character, and reaches only its own name's resolved key;
 *  - Home's edit mode and the key question never both hold the controller ([KeyPick.decide]'s editing, [KeyPick.editMayOpen]); a hold
 *    refused while the question is up is swallowed (no Chat room / map module on the lift);
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

    @Test fun aHeldEditNeverGoesToAnotherNameAndWaitsForItsOwn() {
        val p = fresh()
        twoRecords(p)
        feed(dpad(asking))
        HomeLayoutStore.update { it.copy(card = HomeLayout.CardDock.ABOVE_CHAT) }
        // a relog to another character while asking: the held edit is Korvash's only
        feed(dpad("""{"n":"Thrandil","r":"$r7","rs":"guid"}"""))
        assertEquals("Thrandil-$r7", HomeLayoutStore.who)
        assertTrue(HomeSettings.layout.card != HomeLayout.CardDock.ABOVE_CHAT)
        assertTrue(rec(p, "Thrandil-$r7").card != HomeLayout.CardDock.ABOVE_CHAT)
        // the 1515 merge's review: one name is one character - Korvash's edit waits for Korvash's own key (kept by name, not dropped)
        feed(dpad(resolved(r7, "guid")))
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, rec(p, k7).card)
        assertTrue("laid on: no longer held", HomeLayoutStore.heldOf("Korvash") == null && !p.m.containsKey(HomeLayoutStore.KEY_HELD))
        assertTrue(HomeLayoutStore.carryOk(k7, "Korvash")); assertFalse(HomeLayoutStore.carryOk(k7, "Thrandil"))
        assertFalse("a partial key never", HomeLayoutStore.carryOk("Korvash-", "Korvash")); assertFalse(HomeLayoutStore.carryOk(k7, null))
    }

    /** the 1515 merge's review (finding 1): after "Later" the add-on has no key for the whole login - an edit saved then survives an app
     *  restart (Thor sleep, low memory, an update) and reaches the key picked at the next login, changed fields only */
    @Test fun anEditSavedAfterLaterSurvivesARestartAndReachesThePick() {
        val p = fresh()
        twoRecords(p)
        val k8Before = p.m["homeLayout.$k8"]; val k7Before = rec(p, k7)
        feed(dpad(asking))                                                     // the question; the player says Later: still asking
        val opened = HomeLayoutStore.owner(); val base = HomeSettings.layout
        val draft = base.copy(chatSide = HomeLayout.Side.RIGHT, chatShare = HomeLayout.Share.TWO_THIRDS)
        assertEquals(HomeLayoutStore.EditSave.SAME, HomeLayoutStore.editSaveFor(opened))
        HomeSettings.saveLayout(HomeLayoutStore.editSave(opened, base, draft)!!)                // edit mode's Save
        assertEquals(HomeLayout.Share.TWO_THIRDS, HomeSettings.layout.chatShare)
        assertTrue("kept on disk, by name", p.m.containsKey(HomeLayoutStore.KEY_HELD))
        assertTrue("never under a key", p.m.keys.filter { it.startsWith(HomeLayoutStore.KEY_PREFIX) }.all { CharKey.isKey(it.removePrefix(HomeLayoutStore.KEY_PREFIX)) })
        assertFalse("the held entry is no per-character record", HomeLayoutStore.isCharRecord(HomeLayoutStore.KEY_HELD))
        assertEquals(k8Before, p.m["homeLayout.$k8"])
        // the app process dies; a new one starts on the same prefs, the same login (Later kept): still asking
        HomeSettings.startForTest(p)
        assertNull(HomeLayoutStore.who)
        feed(dpad(asking))
        assertTrue(HomeLayoutStore.pending)
        assertEquals("Home shows the saved edit again", HomeLayout.Share.TWO_THIRDS, HomeSettings.layout.chatShare)
        assertEquals(HomeLayout.Side.RIGHT, HomeSettings.layout.chatSide)
        // a facade's change in the new process goes on top of it
        HomeLayoutStore.update { it.copy(card = HomeLayout.CardDock.ABOVE_CHAT) }
        // another restart, then the next login: the player picks Realm 7
        HomeSettings.startForTest(p)
        feed(dpad(asking))
        feed(dpad(resolved(r7, "picked")))
        assertEquals(k7, HomeLayoutStore.who)
        val now = rec(p, k7)
        assertEquals(HomeLayout.Share.TWO_THIRDS, now.chatShare); assertEquals(HomeLayout.Side.RIGHT, now.chatSide)
        assertEquals(HomeLayout.CardDock.ABOVE_CHAT, now.card)
        assertEquals("Realm 7's own rest kept", k7Before.rest, now.rest)
        assertEquals("Realm 8 untouched", k8Before, p.m["homeLayout.$k8"])
        assertFalse("laid on: the held entry goes", p.m.containsKey(HomeLayoutStore.KEY_HELD))
        // a restart with the key known: nothing laid on again
        HomeSettings.startForTest(p)
        feed(dpad(resolved(r7, "guid")))
        HomeLayoutStore.set(rec(p, k7).copy(card = HomeLayout.CardDock.ON_MAP))
        HomeSettings.startForTest(p)
        feed(dpad(resolved(r7, "guid")))
        assertEquals(HomeLayout.CardDock.ON_MAP, rec(p, k7).card)
    }

    @Test fun aResetDecidesTheHeldEdit() {
        val p = fresh()
        twoRecords(p)
        feed(dpad(asking))
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        val L = setOf(ResetPlan.Kind.LAYOUTS)
        val keys = p.m.keys.toSet()
        assertTrue(HomeLayoutStore.KEY_HELD in keys)
        assertTrue("This character (asking: no key): kept", HomeLayoutStore.KEY_HELD !in ResetPlan.prefKeys("home", keys, L, null, false))
        assertTrue("This character of another key: kept", HomeLayoutStore.KEY_HELD !in ResetPlan.prefKeys("home", keys, L, k8, false))
        assertTrue("All characters: taken", HomeLayoutStore.KEY_HELD in ResetPlan.prefKeys("home", keys, L, k8, true))
        // a reset that left it (another kind, This character): Home still shows it
        HomeLayoutStore.resetDone()
        assertEquals(HomeLayout.Share.THIRD, HomeSettings.layout.chatShare)
        // All characters' Layouts took it: gone from Home too, never laid on later
        p.m.remove(HomeLayoutStore.KEY_HELD); HomeLayoutStore.resetDone()
        assertTrue(HomeSettings.layout.chatShare != HomeLayout.Share.THIRD)
        feed(dpad(resolved(r7, "picked")))
        assertTrue(rec(p, k7).chatShare != HomeLayout.Share.THIRD)
    }

    /** the final review check: a Layouts reset of THIS character while the add-on asks clears the name's held edit too - Home shows
     *  the default, and the key picked afterwards never gets the edit (the reset is not undone) */
    @Test fun aThisCharacterLayoutsResetClearsTheHeldEdit() {
        val p = fresh()
        twoRecords(p)
        feed(dpad(asking))
        HomeLayoutStore.update { it.copy(chatShare = HomeLayout.Share.THIRD) }
        assertTrue(HomeLayoutStore.KEY_HELD in p.m.keys)
        HomeSettings.resetDone(layouts = true)
        assertTrue("Home back on the default", HomeSettings.layout.chatShare != HomeLayout.Share.THIRD)
        assertTrue("the name's held entry is gone", HomeLayoutStore.heldOf("Korvash") == null)
        feed(dpad(resolved(r7, "picked")))
        assertTrue("never laid on the picked key", rec(p, k7).chatShare != HomeLayout.Share.THIRD)
    }

    // ---------------------------------------------------------------- the hold refused while the question is up

    /** the 1515 merge's review (findings 2 and 3): a hold on the chat / the map refused because the key question is up (shown or waiting
     *  for the controller) is swallowed like the fight's refusal - the lift never opens the Chat room or the map module */
    @Test fun aHoldRefusedByTheQuestionIsSwallowed() {
        assertEquals(HomeEditModel.HoldFire.REFUSE, HomeEditModel.holdFire(fight = false, blocked = true, stillOk = true))
        assertEquals(HomeEditModel.HoldFire.REFUSE, HomeEditModel.holdFire(fight = false, blocked = true, stillOk = false))
        assertEquals(HomeEditModel.HoldFire.REFUSE, HomeEditModel.holdFire(fight = true, blocked = false, stillOk = true))
        assertEquals(HomeEditModel.HoldFire.LET_GO, HomeEditModel.holdFire(fight = false, blocked = false, stillOk = false))
        assertEquals(HomeEditModel.HoldFire.OPEN, HomeEditModel.holdFire(fight = false, blocked = false, stillOk = true))
        val src = listOf(java.io.File("../companion/app/src/main/java/com/jrees/wowcompanion"), java.io.File("companion/app/src/main/java/com/jrees/wowcompanion"))
            .first { it.isDirectory }
        val home = java.io.File(src, "HomeView.kt").readText()
        val i = home.indexOf("private fun holdFired()")
        val fired = home.substring(i, home.indexOf("private fun holdStillOk(", i))
        assertTrue(fired.contains("HomeEditModel.holdFire(fightShown(), editBlocked?.invoke() == true, holdStillOk(s))"))
        assertTrue("refused: swallowed to the lift", fired.contains("holdSwallow = true; editRefuse(s); invalidate(); return"))
        assertTrue("an editOpen refusal is swallowed too", fired.contains("else holdSwallow = true"))
        // a swallowed gesture's child is cancelled once (the Chat room / map module never see the UP)
        val disp = home.substring(home.indexOf("override fun dispatchTouchEvent(e: MotionEvent)"))
        assertTrue(disp.substring(0, disp.indexOf("holdWatch(e)")).contains("if (holdTakes || holdSwallow)"))
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
        val keys = setOf("homeLayout.$k7", "homeLayout.$k8", "homeLayout.Thrandil-$r7", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS_LEGACY,
            HomeLayoutStore.KEY_MIGRATED, HomeLayoutStore.KEY_HELD)
        assertEquals("This character: its own record only", setOf("homeLayout.$k8"), ResetPlan.prefKeys("home", keys, L, k8, false))
        assertEquals("All characters: every record, the default and the slots",
            setOf("homeLayout.$k7", "homeLayout.$k8", "homeLayout.Thrandil-$r7", HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS_LEGACY,
                HomeLayoutStore.KEY_HELD),
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
