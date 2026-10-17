package com.jrees.wowcompanion

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * THIRD-PARTY ADD-ONS THE PLAYER OPTS INTO (Settings > Add-ons; the user, 1.0.9, on Spoken: "Modules, don't download by default but
 * make an addon section of our settings"). Unlike [DependencyAddons] (QuestieDB: needed, installed by itself), nothing here is
 * downloaded until the player switches a module on, each module says its size first, and every version is PINNED to one we have
 * tested (an update is offered, never taken by itself). Never inside the APK: the project's own GitHub releases.
 *
 * The shape is general, for the next ones (Map's Polestar, maybe QuestTrail): an [Addon] has its core package, its [Module]s (each
 * its own package, switched on alone) and its [Option]s (the companion's own settings for it). One registry line per add-on; the
 * Settings room draws every add-on as one block (SettingsView.drawAddon) and the tab test counts its rows.
 */
object ThirdPartyAddons {
    /** one package on the project's GitHub releases: its [tag], its [asset], about [mb] megabytes, the AddOns [folders] it brings */
    data class Pkg(val tag: String, val asset: String, val mb: Int, val folders: List<String>)
    /** a part the player switches on by itself: its words, and the package it needs (the [Addon.core] comes with the first one) */
    data class Module(val id: String, val title: String, val what: String, val pkg: Pkg)
    /** a setting of ours for the add-on: on / off, its two lines, its default */
    data class Option(val id: String, val title: String, val on: String, val off: String, val default: Boolean)
    data class Addon(val id: String, val title: String, val what: String, val by: String, val license: String, val repo: String,
                     val version: String, val core: Pkg, val modules: List<Module>, val options: List<Option>) {
        /** the rows' words, as the Settings tabs count them (SettingsTabs.Block.rows) */
        fun rowWords(): List<String> = listOf(title) + modules.map { "$title: ${it.title}" } + options.map { it.title }
    }

    /**
     * SPOKEN (rusty-key/spoken-wow, MIT, by rustykey): quest and NPC dialogue, zone lore and books read aloud in AI voices. Version
     * 3.4.0, the release read on 2026-10-17; the voice packs are its separate releases. The user's rulings: modules opt-in, its own
     * sound CVar writes ALLOWED (an accepted exception to leave-no-trace), its top-screen windows hidden, the stop rules ours.
     */
    val SPOKEN = Addon(
        id = "spoken", title = "Spoken", what = "Reads quests, NPCs, zone lore and books aloud in AI voices", by = "rustykey",
        license = "MIT", repo = "rusty-key/spoken-wow", version = "3.4.0",
        core = Pkg("spoken/v3.4.0", "Spoken-3.4.0.zip", 66, listOf("Spoken", "Spoken_Quests", "Spoken_Places", "Spoken_Readables")),
        modules = listOf(
            Module("quests", "Quests", "Quest text and NPC greetings as you talk to them",
                Pkg("quests-audio/v2.3.0", "SpokenQuestsAudioAll-2.3.0.zip", 1468, listOf("SpokenQuestsAudio"))),
            Module("zones", "Zones", "Zone lore beside the world map",
                Pkg("zones-audio/v2.0.1", "SpokenZonesAudio-2.0.1.zip", 447, listOf("SpokenZonesAudio"))),
            Module("books", "Books", "Books and letters, page by page",
                Pkg("books-audio/v2.1.2", "SpokenBooksAudio-2.1.2.zip", 447, listOf("SpokenBooksAudio")))),
        options = listOf(
            Option("stopClose", "Stop speech on B, Decline or close", "A line stops when you leave the NPC.",
                "A line keeps reading after you leave the NPC.", default = true),
            Option("stopAccept", "Stop speech on Accept", "Accepting a quest stops its line.",
                "Accepting a quest lets its line finish.", default = false)))

    val ALL = listOf(SPOKEN)

    /** "1.5 GB" / "447 MB" */
    fun size(mb: Int): String = if (mb >= 1000) "%.1f GB".format(java.util.Locale.ROOT, mb / 1000.0) else "$mb MB"

    /** what switching [m] on would download now: its package, plus the add-on itself when no module is on yet */
    fun downloadMb(a: Addon, m: Module): Int = m.pkg.mb + (if (a.modules.none { it.id != m.id && moduleOn(a, it) }) a.core.mb else 0)

    // ---- the player's choices (private prefs; nothing is on until the player says so)
    private var prefs: SharedPreferences? = null
    fun init(c: Context) { if (prefs == null) prefs = c.applicationContext.getSharedPreferences("addons", Context.MODE_PRIVATE) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun onChange(l: () -> Unit) { listeners.add(l) }
    private fun changed() { for (l in listeners) runCatching { l() } }

    fun moduleOn(a: Addon, m: Module): Boolean = prefs?.getBoolean("${a.id}.${m.id}", false) ?: false
    fun setModule(a: Addon, m: Module, on: Boolean) { prefs?.edit()?.putBoolean("${a.id}.${m.id}", on)?.apply(); changed() }
    fun option(a: Addon, o: Option): Boolean = prefs?.getBoolean("${a.id}.opt.${o.id}", o.default) ?: o.default
    fun setOption(a: Addon, o: Option, on: Boolean) { prefs?.edit()?.putBoolean("${a.id}.opt.${o.id}", on)?.apply(); changed() }
    fun anyOn(a: Addon): Boolean = a.modules.any { moduleOn(a, it) }
}
