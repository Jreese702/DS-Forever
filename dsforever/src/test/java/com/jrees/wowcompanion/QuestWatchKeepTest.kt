package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kept watch list (the user's "Restore silently"), off-device: ONLY after an unclean end, only into an empty list */
class QuestWatchKeepTest {
    private class Mem : QuestWatchKeep.Store {
        val m = HashMap<String, String>()
        override fun get(k: String) = m[k]
        override fun put(k: String, v: String?) { if (v == null) m.remove(k) else m[k] = v }
    }
    private fun q(id: Int, w: Boolean, slot: Int = id % 40) = QuestWatchKeep.Q(id, slot, w)
    private val log = listOf(q(11, true), q(12, true), q(13, false), q(14, true))
    private val empty = log.map { it.copy(watched = false) }

    /** a session that kept [log]'s three watched quests, then ended without a bye */
    private fun killed(): Pair<Mem, QuestWatchKeep.Model> {
        val s = Mem(); val m = QuestWatchKeep.Model(s)
        m.character("Thrall-Forever"); m.newSession()
        assertTrue(m.onQuests(log, canAct = true).isEmpty())
        val m2 = QuestWatchKeep.Model(s)              // the app (and the game) killed: a new life, no bye
        m2.character("Thrall-Forever"); m2.newSession()
        return s to m2
    }

    @Test fun afterAKillTheEmptyListComesBack() {
        val (_, m) = killed()
        assertEquals(listOf(11, 12, 14), m.onQuests(empty, canAct = true).map { it.id })
    }

    @Test fun afterACleanEndNothing() {
        val s = Mem(); val m = QuestWatchKeep.Model(s)
        m.character("Thrall-Forever"); m.newSession(); m.onQuests(log, true)
        m.cleanEnd()                                   // the bye: the game wrote its state
        val m2 = QuestWatchKeep.Model(s); m2.character("Thrall-Forever"); m2.newSession()
        assertTrue(m2.onQuests(empty, true).isEmpty())  // an empty list after a clean end is the player's own
    }

    @Test fun aListThatCameBackIsLeftAlone() {
        val (_, m) = killed()
        assertTrue(m.onQuests(log.map { if (it.id == 11) it else it.copy(watched = false) }, true).isEmpty())
    }

    @Test fun onlyQuestsStillInTheLogAndWithAColumn() {
        val (_, m) = killed()
        val now = listOf(q(12, false), q(14, false, slot = -1), q(99, false))   // 11 turned in, 14 has no column
        assertEquals(listOf(12), m.onQuests(now, true).map { it.id })
    }

    @Test fun onceASession() {
        val (_, m) = killed()
        assertEquals(3, m.onQuests(empty, true).size)
        assertTrue(m.onQuests(empty, true).isEmpty())            // the next document of the same session: no second restore
    }

    @Test fun waitsForAMomentItCanAct() {
        val (_, m) = killed()
        assertTrue(m.onQuests(empty, canAct = false).isEmpty())   // in a fight: decide later, keep nothing
        assertEquals(3, m.onQuests(empty, canAct = true).size)
    }

    @Test fun anUnloadedLogDecidesNothing() {
        val (_, m) = killed()
        assertTrue(m.onQuests(emptyList(), true).isEmpty())
        assertEquals(3, m.onQuests(empty, true).size)
    }

    @Test fun perCharacter() {
        val (s, _) = killed()
        val m = QuestWatchKeep.Model(s); m.character("Jaina-Forever"); m.newSession()
        assertTrue(m.onQuests(empty, true).isEmpty())          // another character's list is not this one's
    }

    @Test fun atMostTwentyFive() {
        val s = Mem(); val m = QuestWatchKeep.Model(s)
        val many = (1..30).map { q(it, true) }
        m.character("A-B"); m.newSession(); m.onQuests(many, true)
        val m2 = QuestWatchKeep.Model(s); m2.character("A-B"); m2.newSession()
        assertEquals(25, m2.onQuests(many.map { it.copy(watched = false) }, true).size)
    }

    @Test fun thePlayerUntrackingEverythingIsKept() {
        val s = Mem(); val m = QuestWatchKeep.Model(s)
        m.character("A-B"); m.newSession(); m.onQuests(log, true)
        m.onQuests(empty, true)                                 // the player untracked all, then the game was killed
        val m2 = QuestWatchKeep.Model(s); m2.character("A-B"); m2.newSession()
        assertTrue(m2.onQuests(empty, true).isEmpty())
    }
}
