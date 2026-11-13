package com.jrees.wowcompanion

import android.content.Context

/**
 * SETUP PRESETS (GitHub #170, a 1.0.25 player's report, in French: a "juste milieu" between the plain WoW launcher and full DS Forever;
 * the bottom-screen integration is "très poussé" and the player gets lost). Settings > General > Setup: three tiles, and a one-time
 * picker on the launcher's bottom screen before Play (SetupFlow's "setup" prompt: D-pad + A picks, B keeps what is there).
 *
 * A PRESET IS A NAMED BUNDLE OF SWITCHES THE APP ALREADY HAS, written in one tap. App-only: no add-on change, no trace (every switch
 * below is one the app keeps and reconciles itself: GameUi's element modes, the cast bar, Party mode; the Polestar quest guide).
 * Per-character switches (DS party frames, Combat controls, the DS WoW toolbar, Smart Y ...) stay as they are: a preset flips the
 * app-wide ones only. Rooms stay on the bar in every preset (the rooms-same-every-route ruling).
 *
 *   CLASSIC   the game's own UI fully On on top (every GameUiPolicy element ON: no Nintendo bar, nothing hidden), the cast bar shown,
 *             Party mode off, quest tracking Vanilla (the Polestar guide off); the bottom screen is Home's map and chat
 *   BALANCED  Dynamic for the minimap, the chat, the controller bars (and the quest tracker): the game's copy shows except while the
 *             room / Home that replaces it shows; the NPC windows and the cast bar as Full
 *   FULL      today's defaults (UiElements.KNOWN's defaults, the cast bar hidden, Party mode on)
 *
 * "Custom" = the switches match no preset ([matching] null): any manual change after a pick shows it, and going back to a preset's
 * exact values shows that preset again. Nothing else is stored: the switches ARE the state.
 *
 * The rules ([bundle], [matches], [matching]) are pure; [apply] / [current] read and write the real stores.
 */
object SetupPresets {
    enum class Preset(val label: String) { CLASSIC("Classic"), BALANCED("Balanced"), FULL("Full") }

    /** the tiles' words: the three presets, then the state no preset matches (shown, never pressed) */
    const val CUSTOM = "Custom"

    /**
     * what a preset sets: every [UiElements] mode, the game's cast bar Hide ([GameUi.castBarHide]), Party mode ([GameUi.SW_PMODE]) and,
     * when [vanilla], quest tracking Vanilla (the Polestar guide off). [vanilla] false = the guide is left as the player chose it
     */
    data class Bundle(val modes: Map<String, UiMode>, val castBarHide: Boolean, val partyMode: Boolean, val vanilla: Boolean)

    /** the switches as they are now */
    data class Now(val modes: Map<String, UiMode>, val castBarHide: Boolean, val partyMode: Boolean, val questTracking: String)

    private val IDS = UiElements.KNOWN.map { it.id }

    fun bundle(p: Preset): Bundle = when (p) {
        Preset.CLASSIC -> Bundle(IDS.associateWith { UiMode.ON }, castBarHide = false, partyMode = false, vanilla = true)
        Preset.BALANCED -> Bundle(IDS.associateWith { id ->
            when (id) { "minimap", "chat", "hud", "tracker" -> UiMode.DYNAMIC; else -> UiElements.of(id)!!.default } },
            castBarHide = true, partyMode = true, vanilla = false)
        Preset.FULL -> Bundle(IDS.associateWith { UiElements.of(it)!!.default }, castBarHide = true, partyMode = true, vanilla = false)
    }

    /** [now] is exactly what [p] writes (a mode missing from [now] counts as its element's default) */
    fun matches(p: Preset, now: Now): Boolean {
        val b = bundle(p)
        return b.modes.all { (id, m) -> (now.modes[id] ?: UiElements.of(id)?.default) == m } &&
            b.castBarHide == now.castBarHide && b.partyMode == now.partyMode && (!b.vanilla || now.questTracking == "vanilla")
    }

    /** the preset [now] is, or null = Custom */
    fun matching(now: Now): Preset? = Preset.values().firstOrNull { matches(it, now) }

    /** the tile lit now: a preset's index, or [Preset] size for Custom */
    fun litIndex(now: Now): Int = matching(now)?.ordinal ?: Preset.values().size

    // ------------------------------------------------------------------ the real stores

    /** the stores a pick writes, ready (the launcher's panel and the Settings room call it; each init is idempotent) */
    fun init(c: Context) { GameUi.init(c); Settings.init(c) }

    fun current(): Now = Now(IDS.associateWith { GameUi.mode(it) }, GameUi.castBarHide(), GameUi.swWant(GameUi.SW_PMODE),
        Settings.questTracking)

    /** the pick: the bundle written (GameUi's part in one write, one reconcile: its taps follow at their own pace) */
    fun apply(p: Preset) {
        val b = bundle(p)
        GameUi.setBundle(b.modes, b.castBarHide, b.partyMode)
        // Vanilla = the Polestar guide off (a guide not asked about yet is answered too: Classic is the game's own way)
        if (b.vanilla && Settings.polestarGuide != PolestarGuide.OFF) PolestarGuide.set(false)
    }
}
