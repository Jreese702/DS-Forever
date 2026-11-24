package com.jrees.wowcompanion

import androidx.annotation.Keep
import java.io.File

/**
 * The on-device interiors producer. Track B owns the implementation (and this file once it lands); Track C's [IndoorScheduler]
 * drives it one building at a time. Shape proposed by C on 2026-10-12 for B's day-1 agreement.
 *
 * Output contract: [produce] writes ONE building into its staging folder exactly as [IndoorBakeStore] expects (models.json /
 * places.json / interiors.json with only [IndoorBakeStore.Work.key], its level images, outline and height grid). The store
 * re-validates the indexes and grid; geometry and image validation is the producer's job before it returns.
 */
internal interface IndoorGenerator : AutoCloseable {
    /**
     * Called by the producer between bounded phases (after a WMO group, an M2 batch, an ADT tile, a Recast tile, an image).
     * It BLOCKS while the scheduler is paused (game busy, device hot) and throws [Stopped] when the bake must end; the
     * producer lets that propagate, and nothing of that building is published.
     */
    fun interface Yield { @Keep fun point() }  // native code calls point() by name: R8 must keep it (IndoorGeneratorNative.kt)

    class Stopped : RuntimeException("interiors bake stopped")
    /** 2026-11-08: a long pause (the game busy, the player moving): the run lets the game files go and waits outside them */
    class Released : RuntimeException("interiors bake paused long: the game files are let go")
    /**
     * THE BUILDER'S OWN RULE TURNED THIS BUILDING DOWN (the user's D1 pick, 2026-11-23: "Done when all mappable built"): too big for the
     * city rule, no floors found, a budget exceeded... Deterministic for the same inputs and the same builder, so it is SETTLED: it counts
     * toward "finished" and is tried again only when the game build or the builder ([identity]) changes. Anything else that fails
     * (out of memory, an unreadable file, a kill) is transient and tried again on the next run.
     */
    class Rejected(reason: String) : RuntimeException(reason)

    /**
     * Everything that decides the builder's output besides the inputs: its [algorithm] and, for the native one, the library's version
     * and bytes. A rule-rejection and the finished stamp hold for this identity only (a new builder re-arms them).
     */
    val identity: String get() = "a$algorithm"

    companion object {
        /**
         * a native geometry / contract failure ([IndoorGeneratorNative.generate]'s IllegalStateException) that is the builder's own rule,
         * not the device's state: everything but an input that did not read, an allocation or memory failure, or an unexpected error
         */
        fun isRule(message: String?): Boolean {
            val m = message?.trim().orEmpty()
            if (m.isEmpty()) return false
            return TRANSIENT.none { m.contains(it, ignoreCase = true) } && !m.startsWith("interiors generator: ")
        }
        private val TRANSIENT = listOf("unavailable", "out of memory", "allocation limit", "low memory", "could not be read", "unreadable")

        /**
         * a deferral recorded before D1 (1.0.22 - 1.0.39: its reason only, any Throwable's message - a Java out-of-memory "Failed to
         * allocate ...", a full disk "ENOSPC", a building missing from a stale catalogue) is settled only when its reason is one of the
         * builder's known rules (the PC full-catalogue run's 221: E:/wc-int-out/d/fc-d7ed255f.log, 2026-10-13; review of 2026-11-23).
         * Anything else is tried once more and then classified by its kind ([Rejected]): at worst one extra try, never a building that
         * could be built left out while the row says finished.
         */
        fun isKnownRule(reason: String?): Boolean {
            val m = reason?.trim().orEmpty()
            return m.isNotEmpty() && KNOWN_RULES.any { m.startsWith(it) }
        }
        private val KNOWN_RULES = listOf(
            "room exceeds the bounded layout policy",
            "architectural section preview has no storeys",
            "empty_walk_requires_investigation",
            "height grid contains surfaces assigned to no storey or stair",
            "merged Recast span budget exceeded",
            "no section storey has a supporting kept walk surface",
            "room has no measured walk at an actual boundary portal",
            "walk-section face budget exceeded or empty walk",
        )
    }

    /** Bump when unchanged inputs would now give different output; it becomes [IndoorBakeStore.Work.algorithm]. */
    val algorithm: Int

    /**
     * Every building of this game build: key, input digest (sha-256 over the content keys of every file it reads: root WMO,
     * groups, doodad M2s, ADT tiles under it) and world footprints for nearest-first. Must be much cheaper than [produce];
     * the scheduler caches the result per build, so it runs once per game update.
     */
    fun catalogue(yield: Yield): List<IndoorBakeStore.Work>

    /**
     * The map ids of this game build's dungeons and raids (the user's pick, 2026-11-23: "Build last, leave out of count"): a building
     * placed only on these maps is built after every other one and left out of "finished". Null = not known (everything counts).
     */
    fun instanceMaps(): Set<Int>? = null

    /** Writes [work]'s validated outputs into [out] (an empty staging folder). Throws on any failure: the building is deferred. */
    fun produce(work: IndoorBakeStore.Work, out: File, yield: Yield)

    /** Opens a generator over the already-open game files (the scheduler owns and closes both). */
    fun interface Factory { fun open(game: GameFiles): IndoorGenerator }
}
