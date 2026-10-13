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

    /** Bump when unchanged inputs would now give different output; it becomes [IndoorBakeStore.Work.algorithm]. */
    val algorithm: Int

    /**
     * Every building of this game build: key, input digest (sha-256 over the content keys of every file it reads: root WMO,
     * groups, doodad M2s, ADT tiles under it) and world footprints for nearest-first. Must be much cheaper than [produce];
     * the scheduler caches the result per build, so it runs once per game update.
     */
    fun catalogue(yield: Yield): List<IndoorBakeStore.Work>

    /** Writes [work]'s validated outputs into [out] (an empty staging folder). Throws on any failure: the building is deferred. */
    fun produce(work: IndoorBakeStore.Work, out: File, yield: Yield)

    /** Opens a generator over the already-open game files (the scheduler owns and closes both). */
    fun interface Factory { fun open(game: GameFiles): IndoorGenerator }
}
