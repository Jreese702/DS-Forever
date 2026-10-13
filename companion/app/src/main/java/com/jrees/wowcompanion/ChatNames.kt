package com.jrees.wowcompanion

/**
 * What the Chat room can do with a player's name. Pure Kotlin, so it is tested off the device
 * (tools/chat_test). Other rooms ask [canWhisper] before they offer a whisper.
 *
 * A NAME ON THIS CLIENT IS TWO WORDS, a first name and a second name. Chat lines carry it with
 * a space ("Mylie Cyriss"). The game's own chat box takes "First-Last" as well as "First Last"
 * after /w (ChatFrameEditBoxBaseMixin:ExtractTellTarget, read from this build's UI source), and
 * the hyphen makes the name ONE word, so nothing of the message can be read as part of the name.
 *
 * ACCEPTED:
 *   First              one word of plain letters
 *   First Last         as chat lines carry it; sent as First-Last
 *   First-Last         as a list may carry it (Group Finder, Social); sent as it is.
 *                      "Name-Realm" has the same shape, and after a hyphen digits are taken too
 *                      (a realm such as Area52). What the game makes of a realm is the game's
 *                      business: the line is the one the player would type by hand.
 * REFUSED:
 *   accents            `input text` cannot carry them
 *   First Last-Realm   a space AND a hyphen: it would go out as First-Last-Realm, and nobody has
 *                      shown what the game makes of that. A whisper cannot be taken back, so a
 *                      name whose reading is a guess is not sent.
 *   anything else      two hyphens, three words, an apostrophe, an empty name
 */
object ChatNames {
    private val NAME = Regex("^[A-Za-z]+(?: [A-Za-z]+|-[A-Za-z0-9]+)?$")

    /** true when the Chat room will whisper this name */
    fun canWhisper(name: String) = NAME.matches(name.trim())

    /** the name as it is typed after /w: one word */
    fun forCommand(name: String) = name.trim().replace(' ', '-')
}
