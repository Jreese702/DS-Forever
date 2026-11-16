package com.jrees.wowcompanion

/**
 * HOME'S MAP BEFORE ITS PICTURES (issues #316 #317 #284, 1.0.32): "the minimap on bottom screen does not display correctly. its black
 * with blue zones" (#316, #317 on a Thor Max) and "game art shows its finished but map is still empty" (#284). Both reports' art had
 * the world map done and Home's map pictures (the minimap step) not made: the background art builds them only while the game is
 * closed (PlayGate, every Thor), and "Game art: ready" on the start screen is the art Play needs. With no tile on the device the small
 * map drew only its dark ground, the faint grid and the quests' blue areas, and said nothing. Now it says what the Map room already
 * says in the same case (its note, es / fr there already), where the terrain would be, until the minimap step's index is written.
 *
 * Pure (the tests): MapView draws it, MinimapTiles.building says whether the pictures are still to be made.
 */
object HomeMapNote {
    /** the Map room's own words, as WorldMapRoomView's NO_ART_NOTE (translated in es / fr; HomeMapNoteTest keeps the two the same) */
    const val TEXT = "Map pictures build while the game is closed"
    /** the note's text size, and the smallest a narrow map shrinks it to */
    const val SIZE = 20f
    const val MIN = 13f
    /** the room left on each side of the field */
    const val PAD = 16f

    /** the note shows: no terrain drawn this frame, Home's map pictures still to be made, and not in Home's open / close glide */
    fun shows(morphing: Boolean, terrain: Boolean, building: Boolean): Boolean = !morphing && !terrain && building

    /**
     * The terrain this frame ([shows]' terrain): tiles to draw here ([tiled]: this continent's tile folder, a view of a few tiles) and
     * one drawn ([drew]), or some still loading ([allAbsent] false) while the note is not up ([showing]). Review of #316 #317: the minimap
     * step part-way makes the continent's folder before the tiles where the player is, and the field was black with no note; a note
     * already up stays until a tile lands (no blink as each of the step's refreshes loads the missing tiles again).
     */
    fun terrainShown(tiled: Boolean, drew: Boolean, allAbsent: Boolean, showing: Boolean): Boolean =
        tiled && (drew || (!allAbsent && !showing))

    /** the text size at which a line [measured] px wide at [size] fits [width] px (never under [MIN]) */
    fun fit(measured: Float, size: Float, width: Float): Float =
        if (measured <= 0f || width <= 0f || measured <= width) size else (size * width / measured).coerceAtLeast(MIN)

    /** the note's baseline: a little over half way down from the player ([playerY]) to the field's bottom ([bottom]), under the arrow */
    fun baseline(playerY: Float, bottom: Float): Float = playerY + (bottom - playerY) * 0.55f
}
