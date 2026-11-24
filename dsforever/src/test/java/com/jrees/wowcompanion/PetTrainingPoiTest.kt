package com.jrees.wowcompanion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user, 2026-11-24 (a Hunter on 1.0.40): "Using my pet training is showing up as a trainer on my map and that is not correct".
 * Beast Training opens the trainer window with no NPC behind it; Harvest.lua hv2 filed it as a trainer where the player stood, with no
 * creature id (no "npc" unit; the target the pet / nothing; or "npc" the player). Harvest.lua hv3 records an NPC's service only with
 * the NPC's creature id (tools/test_harvest_npc.py); the app now refuses an id-less NPC service too, and drops the ones already stored
 * in poi_harvest.json at load. Objects (mailbox, guild vault) have no id and keep their pins.
 */
class PetTrainingPoiTest {
    @Test fun anNpcServiceNeedsItsCreatureId() {
        for (svc in listOf("trainer", "vendor", "bank", "auction", "flightmaster", "innkeeper", "stable", "tabard")) {
            assertTrue("$svc without an id: no NPC behind the window", poiNoNpc(svc, null))
            assertFalse("$svc with an id: an NPC's", poiNoNpc(svc, 3039))
        }
        assertFalse("a mailbox is an object", poiNoNpc("mailbox", null))
        assertFalse("a guild vault is an object", poiNoNpc("guildbank", null))
    }

    @Test fun theStoredBeastTrainingPinsGo() {
        // the file's own shape (PoiHarvest.toJson): m, id?, n?, s, x, y, wx?, wy?, c?, k?, r?, vis, first, last, ts
        val recs = JSONArray(
            """[
            {"m":1453,"n":"Wolfie","s":["trainer"],"x":0.51,"y":0.52,"wx":100.0,"wy":200.0,"k":"class","vis":3,"first":1,"last":2,"ts":[1000]},
            {"m":1453,"s":["trainer"],"x":0.61,"y":0.62,"k":"class","vis":1,"first":1,"last":2,"ts":[]},
            {"m":1453,"n":"Korvash","s":["trainer"],"x":0.71,"y":0.72,"vis":1,"first":1,"last":2,"ts":[]},
            {"m":1453,"id":3039,"n":"Dazalar","s":["trainer"],"x":0.40,"y":0.41,"k":"class","vis":5,"first":1,"last":2,"ts":[]},
            {"m":1453,"id":1213,"n":"Brother Danil","s":["innkeeper","vendor"],"x":0.30,"y":0.31,"r":true,"vis":2,"first":1,"last":2,"ts":[]},
            {"m":1453,"s":["mailbox"],"x":0.20,"y":0.21,"vis":4,"first":1,"last":2,"ts":[]},
            {"m":1453,"s":["vendor"],"x":0.10,"y":0.11,"vis":1,"first":1,"last":2,"ts":[]}
            ]""")
        val (kept, dropped) = poiHarvestCleaned(recs)
        assertEquals("the three id-less trainers and the id-less vendor", 4, dropped)
        assertEquals(3, kept.length())
        val names = (0 until kept.length()).map { kept.getJSONObject(it) }
        assertEquals(3039, names[0].getInt("id"))
        assertEquals(1213, names[1].getInt("id"))
        assertEquals("the mailbox (an object) stays", "mailbox", names[2].getJSONArray("s").getString(0))
        // the kept records are the very same rows, untouched
        assertEquals(recs.getJSONObject(3).toString(), names[0].toString())
    }

    @Test fun anIdLessRecordKeepsOnlyItsObjectServices() {
        val recs = JSONArray("""[{"m":1,"s":["mailbox","trainer"],"x":0.2,"y":0.2,"vis":1,"first":1,"last":2,"ts":[]}]""")
        val (kept, dropped) = poiHarvestCleaned(recs)
        assertEquals(1, dropped)
        assertEquals(1, kept.length())
        assertEquals(JSONArray("""["mailbox"]""").toString(), kept.getJSONObject(0).getJSONArray("s").toString())
    }

    @Test fun aCleanFileIsLeftAlone() {
        val recs = JSONArray("""[{"m":1,"id":5,"s":["trainer"],"x":0.2,"y":0.2,"vis":1,"first":1,"last":2,"ts":[]},
            {"m":1,"s":["mailbox"],"x":0.3,"y":0.3,"vis":1,"first":1,"last":2,"ts":[]}]""")
        val (kept, dropped) = poiHarvestCleaned(recs)
        assertEquals(0, dropped)
        assertEquals(recs.toString(), kept.toString())
        // a record without its service list is the loader's to refuse (fromJson), not counted here
        val (k2, d2) = poiHarvestCleaned(JSONArray("""[{"m":1,"x":0.1,"y":0.1}]"""))
        assertEquals(0, d2)
        assertEquals(1, k2.length())
    }

    @Test fun theIngestRuleMatchesTheAddOn() {
        // Beast Training's "poi" line from an hv2 add-on (no id) is refused; an NPC's line (with its id) and a mailbox's are taken
        assertTrue(poiNoNpc(JSONObject("""{"svc":"trainer","mapID":1453,"x":0.5,"y":0.5,"name":"Wolfie","src":"TRAINER_SHOW"}""")))
        assertTrue(poiNoNpc(JSONObject("""{"svc":"trainer","mapID":1453,"x":0.5,"y":0.5,"src":"PIM"}""")))
        assertFalse(poiNoNpc(JSONObject("""{"svc":"trainer","mapID":1453,"x":0.5,"y":0.5,"id":3039,"name":"Dazalar"}""")))
        assertFalse(poiNoNpc(JSONObject("""{"svc":"mailbox","mapID":1453,"x":0.5,"y":0.5}""")))
        // a guild vault's line never carries a creature id into the store (hv2 could borrow the target's)
        assertEquals(null, poiIdOf(JSONObject("""{"svc":"guildbank","id":299}""")))
        assertEquals(null, poiIdOf(JSONObject("""{"svc":"mailbox","id":299}""")))
        assertEquals(3039, poiIdOf(JSONObject("""{"svc":"trainer","id":3039}""")))
    }
}
