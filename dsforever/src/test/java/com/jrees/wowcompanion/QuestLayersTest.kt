package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** the map's quest layers (the user's Q1, "Tabs as drawn"): the switches' defaults and the small map's rule */
class QuestLayersTest {
    @Test fun theDefaultsAreTheMocks() {
        val kept = HashMap<String, Boolean>()
        val l = QuestLayers({ k, d -> kept[k] ?: d }, { k, v -> kept[k] = v })
        val on = QuestLayers.Layer.values().filter { l.on(it) }.map { it.name }
        // low-level off; danger off; travel on (b-travel-layer); untracked off (the user, 2026-11-17: "Untracked should be toggle off by
        // default in the filters")
        assertEquals(listOf("AVAILABLE", "TRACKED", "TURN_INS", "DAILY", "SMALL", "TRAVEL"), on)
        assertFalse("untracked quest objectives off by default", QuestLayers.Layer.OTHER.default)
        l.toggle(QuestLayers.Layer.LOW); assertTrue(l.on(QuestLayers.Layer.LOW))
        assertFalse(l.on(QuestLayers.Layer.OTHER))
        l.toggle(QuestLayers.Layer.OTHER); assertTrue(l.on(QuestLayers.Layer.OTHER))
    }

    @Test fun theSmallMapShowsOnlyNearbyGiversAndTheTrackedQuests() {
        assertTrue(QuestLayers.smallMapShows(true, true, QuestLayers.Kind.AVAIL, 150.0))
        assertFalse(QuestLayers.smallMapShows(true, true, QuestLayers.Kind.AVAIL, 250.0))
        assertFalse(QuestLayers.smallMapShows(true, false, QuestLayers.Kind.AVAIL, 50.0))           // its switch off
        assertFalse(QuestLayers.smallMapShows(true, true, QuestLayers.Kind.OTHER_OBJECTIVE, 10.0))
        assertTrue(QuestLayers.smallMapShows(true, true, QuestLayers.Kind.TRACKED, 0.0))
        assertTrue(QuestLayers.smallMapShows(false, false, QuestLayers.Kind.OTHER_OBJECTIVE, 900.0))  // the open map: its own switches only
    }
}
