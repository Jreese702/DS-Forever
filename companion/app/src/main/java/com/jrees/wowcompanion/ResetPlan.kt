package com.jrees.wowcompanion

/**
 * RESET TO DEFAULTS, WHAT EACH BUTTON TOUCHES (the user, 2026-11-19, relayed from a player whose pushed setting raised the device's
 * temperature by about 10 degrees: "DS Forever needs reset to defaults buttons, split into four"; the picks: a folding Reset group in
 * Settings > General above Support the Project; Layouts and Skills = the CURRENT character only; Everything asks This character / All
 * characters). Pure data: ResetPlanTest proves every store key is in exactly one bucket and each reset touches only its own.
 *
 * THE INVENTORY (every store DS Forever keeps, 1.0.36 test 1504; "per char" = keyed by the character's "Name-Realm"):
 *
 * ADD-ON SAVED TABLES (WoW SavedVariables; the app keeps a copy, SvKeep, and the merges respect a reset's marks, SvReset)
 *  WowCompanionSpellsDB  SKILLS  (per char) faceBy, fightBy, formFightBy, cmdBy, binds, stickBy, attackBy, ccOff, lowPickBy, smartOn,
 *                                 smartBuffs, partyBy, radialPins, sradials (+ the legacy sradial it is rebuilt from), castBar
 *                        LAYOUTS (per char) dpad (the DS WoW toolbar; back to its default ON, never "not asked": the prompt stays answered)
 *                        NEVER   formSlotsBy, smartModel, cdLearn, petSpellBy, petDeadBy (learned / remembered game facts); dpadZL,
 *                                 srGone, srBy, srMacros, genUse, fix181, keyProbe, aclOurs, aclReset1 (one-time clean-ups / probes: a
 *                                 reset would run them again); faceSwap, faceCd, combatFace (crash recovery / learned, never synced);
 *                                 macroRoom (the Macros room's last tab); attack (legacy shared record); padLayout, startOver, castBarTab
 *                                 (the add-on's copies of app settings: the app tells them again from its own setting)
 *  WowCompanionDB        LAYOUTS (per char) lay, hubSw, hubLy, meters, prdY, prdS, prdW (the top screen remembers your look: the layout
 *                                 editor's pieces incl. the party block, the switch taps, the layout mask + cast bar hide, the meters, the HUD)
 *                        SKILLS  (per char) bagBinds (items on controller chords)
 *                        SETTINGS (account) compass, hideTips, aggroRings, lootTop (Top screen switches kept by the add-on)
 *                        NEVER   pqol, pskip (Polestar); npEnemies, npFriends, npFriendsOff, npFriendsSet (the player's OWN nameplate
 *                                 values the add-on put aside: it gives them back itself when its plate switches go off); routeWp, chatTop,
 *                                 compassCal, compassCam (an experiment), layV, prdYv, prdCurve, camProbe, calProbe, qtDefaults,
 *                                 chatStyleSet2, cursorSoftSet (one-time moves, probes, calibrations: a reset would run them again)
 *
 * APP PREFERENCES (SharedPreferences files)
 *  settings     SETTINGS lighterMode, lowMemGraphics, homeMapFull, homeMapTerrain, indoorBuild, swapScreens, voiceTyping, minimapYd,
 *                        minimapIndoorYd, worldMapFog, avoidDanger, chatDefaultInput, theme
 *               NEVER    browserLogin, windowsId (+ windowsIdRan*), gameAccountAsk (the sign-in), polestarGuide, polestarOffBoot,
 *                        questTracking* (Polestar), theme_custom (the player's own made theme: Classic comes back, the made one stays),
 *                        swapScreensFails, settingsTab, the migration marks
 *  framegen     SETTINGS enabled, multiplier, flowScale, performance, fpsLimitContainer, fpsLimitAsk   NEVER fpsLimit108, fpsLimitNote
 *  gameui       SETTINGS mode.<element>, sw.pmode, castbar.hide   LAYOUTS (per char) sw.<part>.<who>, hud.<who>, prdy./prdw./prds.<who>,
 *                        mt.<part>.<who>
 *  cast         SETTINGS buttonLayout, abxyLocation
 *  home         SETTINGS pauseOverGame   LAYOUTS (app-wide: Home has no per-character store) rest, questMin, questOverMap, combatGrow,
 *                        swingTimer, castBarSide, castStrip, castBarCombat, clockHearth, clockTeleport, clockGold, clockBags, clockAmmo,
 *                        and the wheels' open / pick: radialTap, radialPickA, spellTap, spellPickA, classTapL, classPickAL, classTapR,
 *                        classPickAR, pauseTap, pausePickA   SKILLS (per char) smartPet.<who>, petFollowB.<who> (B: pet recall)
 *               NEVER    castBarAsked, ruling.hide.2026-09-28 (asked once), holdTalk.<who>, talkSendNow.<who> (chat: not named in the
 *                        rulings - left, a decision)
 *  dpad         LAYOUTS (per char) choice_<who> (set ON, pending until the game says it)   NEVER pend_/owed_ (the prompt's own state)
 *  radial_meta  SKILLS  (per char) all[<who>] (the spell radials' names, icons, tap / hold)
 *  wc_radial    SKILLS  (per char) doc, sdoc (the radials' kept documents, when they are that character's: "who")
 *  mapfilters, maproom, mapprobe  SETTINGS (every key: the map's filters; the open map keeps its own until DS Forever starts again)
 *  dsf_lang     SETTINGS lang         dsf_stream  SETTINGS quality   NEVER host, client_id, client_cert, client_key (pairing)
 *  NEVER (whole files) addons (the add-ons the player installed), polestar_qol, polestar_handoff, newspells, wc_itemcd, questwatch,
 *        taxinet, questme, cityexits, interact, chat_filter, chat_talk (decision: chat not named), data_staleness, dsf_art_near, report,
 *        dsf_last_run, dsf_region, voice, home_login_prefs, dsf_conn_test, wow_forever, battle_net_login, crash_logs, the GameNative
 *        DataStore (PluviaPreferences)
 *
 * APP FILES
 *  Documents/DS Forever/combat/<who>.json   SKILLS (per char): the In-combat backup the app puts back when the store is empty at login
 *  Documents/DS Forever/settings/<table>.json  the kept copies: changed per entry above, marks in resets.json
 *  NEVER the art / voice / add-on downloads, reports, logs, login (bnet_login.bin), maps and quest caches
 *
 * GAME SETTINGS (Config.wtf) - Reset settings gives back ONLY what DS Forever recorded an original for: Low-memory graphics on an 8 GB
 * Thor (view distance, particles, spells, shadows: GameConfigWtf.LOW_MEMORY_8GB_SAVED), each key still at DS Forever's value. Nothing
 * else in the game is written (the leave-no-trace rule): the low-memory textures and the start's forced keys have no recorded original.
 */
object ResetPlan {
    enum class Kind { SETTINGS, LAYOUTS, SKILLS }
    enum class Button(val kinds: Set<Kind>) {
        SETTINGS(setOf(Kind.SETTINGS)), LAYOUTS(setOf(Kind.LAYOUTS)), SKILLS(setOf(Kind.SKILLS)),
        EVERYTHING(setOf(Kind.SETTINGS, Kind.LAYOUTS, Kind.SKILLS))
    }

    private const val SPELLS = "WowCompanionSpellsDB"
    private const val DB = "WowCompanionDB"

    /** a top key of one of our saved tables: [perChar] = keyed by "Name-Realm"; [default] = the value a reset gives (null = none) */
    data class SvKey(val variable: String, val top: String, val kind: Kind?, val perChar: Boolean, val default: Any? = null)

    val SV_KEYS: List<SvKey> = listOf(
        *listOf("faceBy", "fightBy", "formFightBy", "cmdBy", "binds", "stickBy", "attackBy", "ccOff", "lowPickBy", "smartOn", "smartBuffs",
            "partyBy", "radialPins", "sradials", "sradial", "castBar").map { SvKey(SPELLS, it, Kind.SKILLS, true) }.toTypedArray(),
        SvKey(SPELLS, "dpad", Kind.LAYOUTS, true, default = true),
        *listOf("lay", "hubSw", "hubLy", "meters", "prdY", "prdS", "prdW").map { SvKey(DB, it, Kind.LAYOUTS, true) }.toTypedArray(),
        SvKey(DB, "bagBinds", Kind.SKILLS, true),
        *listOf("compass", "hideTips", "aggroRings", "lootTop").map { SvKey(DB, it, Kind.SETTINGS, false) }.toTypedArray(),
    )

    /** the keys a reset never touches (the inventory's NEVER rows) */
    val SV_NEVER: Map<String, Set<String>> = mapOf(
        SPELLS to setOf("formSlotsBy", "smartModel", "cdLearn", "petSpellBy", "petDeadBy", "dpadZL", "srGone", "srBy", "srMacros", "genUse",
            "fix181", "keyProbe", "aclOurs", "aclReset1", "faceSwap", "faceCd", "combatFace", "macroRoom", "attack", "padLayout", "startOver",
            "castBarTab"),
        DB to setOf("pqol", "pskip", "npEnemies", "npFriends", "npFriendsOff", "npFriendsSet", "routeWp", "chatTop", "compassCal",
            "compassCam", "layV", "prdYv", "prdCurve", "camProbe", "calProbe", "qtDefaults", "chatStyleSet2", "cursorSoftSet"),
    )

    /** a character key as the add-on writes it ("Name-Realm") */
    fun isCharKey(k: Any): Boolean = k is String && k.indexOf('-') in 1 until k.length - 1

    /**
     * the saved-table entries [kinds] reset: account keys always; a per-character key for [who] only, or with [all] for every character
     * the tables hold ([tables]: variable -> the table as kept / in the game file)
     */
    fun svTargets(kinds: Set<Kind>, who: String?, all: Boolean, tables: Map<String, List<Map<Any, Any>>>): List<SvReset.Target> {
        val out = ArrayList<SvReset.Target>()
        for (k in SV_KEYS) {
            if (k.kind !in kinds) continue
            if (!k.perChar) { out += SvReset.Target(k.variable, k.top, null, k.default); continue }
            val chars = if (all) tables[k.variable].orEmpty().flatMap { t -> (t[k.top] as? Map<*, *>)?.keys.orEmpty().filterNotNull() }
                .filter { isCharKey(it) }.map { it as String }.toSortedSet() + listOfNotNull(who)
                else listOfNotNull(who)
            for (c in chars.distinct()) out += SvReset.Target(k.variable, k.top, c, k.default)
        }
        return out
    }

    /** one preference key's rule: an exact key, or a per-character key "<prefix><who>" (with [suffix] after it); [kind] null = never */
    data class PrefKey(val file: String, val key: String, val kind: Kind?, val perChar: Boolean = false, val wholeFile: Boolean = false)

    private fun exact(file: String, kind: Kind?, vararg keys: String) = keys.map { PrefKey(file, it, kind) }
    private fun char(file: String, kind: Kind?, vararg prefixes: String) = prefixes.map { PrefKey(file, it, kind, perChar = true) }

    val PREFS: List<PrefKey> = listOf(
        exact("settings", Kind.SETTINGS, "lighterMode", "lowMemGraphics", "homeMapFull", "homeMapTerrain", "indoorBuild", "swapScreens",
            "voiceTyping", "minimapYd", "minimapIndoorYd", "worldMapFog", "avoidDanger", "chatDefaultInput", "theme"),
        exact("settings", null, "browserLogin", "windowsId", "windowsIdRan", "windowsIdRanAt", "windowsIdRanOk", "gameAccountAsk",
            "polestarGuide", "polestarOffBoot", "questTracking", "questTrackingSafePath", "questTrackingPolestar1112", "questTrailRetire1112",
            "theme_custom", "swapScreensFails", "settingsTab"),
        exact("framegen", Kind.SETTINGS, "enabled", "multiplier", "flowScale", "performance", "fpsLimitContainer", "fpsLimitAsk"),
        exact("framegen", null, "fpsLimit108", "fpsLimitNote"),
        exact("gameui", Kind.SETTINGS, "sw.pmode", "castbar.hide"),
        char("gameui", Kind.SETTINGS, "mode."),
        char("gameui", Kind.LAYOUTS, "sw.*.", "hud.", "prdy.", "prdw.", "prds.", "mt.*."),
        exact("cast", Kind.SETTINGS, "buttonLayout", "abxyLocation"),
        exact("home", Kind.SETTINGS, "pauseOverGame"),
        exact("home", Kind.LAYOUTS, "rest", "questMin", "questOverMap", "combatGrow", "swingTimer", "castBarSide", "castStrip", "castBarCombat",
            "clockHearth", "clockTeleport", "clockGold", "clockBags", "clockAmmo", "radialTap", "radialPickA", "spellTap", "spellPickA",
            "classTapL", "classPickAL", "classTapR", "classPickAR", "pauseTap", "pausePickA"),
        char("home", Kind.SKILLS, "smartPet.", "petFollowB."),
        exact("home", null, "castBarAsked", "ruling.hide.2026-09-28"),
        char("home", null, "holdTalk.", "talkSendNow."),
        char("dpad", Kind.LAYOUTS, "choice_"),
        char("dpad", null, "pend_choice_", "owed_choice_"),
        exact("dsf_lang", Kind.SETTINGS, "lang"),
        exact("dsf_stream", Kind.SETTINGS, "quality"),
        exact("dsf_stream", null, "host", "client_id", "client_cert", "client_key"),
        listOf(PrefKey("mapfilters", "*", Kind.SETTINGS, wholeFile = true), PrefKey("maproom", "*", Kind.SETTINGS, wholeFile = true),
            PrefKey("mapprobe", "*", Kind.SETTINGS, wholeFile = true)),
    ).flatten()

    /** whole files a reset never opens */
    val NEVER_FILES = setOf("addons", "polestar_qol", "polestar_handoff", "newspells", "wc_itemcd", "questwatch", "taxinet", "questme",
        "cityexits", "interact", "chat_filter", "chat_talk", "data_staleness", "dsf_art_near", "report", "dsf_last_run", "dsf_region", "voice",
        "home_login_prefs", "dsf_conn_test", "wow_forever", "battle_net_login", "crash_logs")

    /** [key] of [file] matches [r]: exact; a per-character prefix ("sw.*." = "sw.<part>."; "mode." takes any element) followed by
     *  [who] (or, with [all], any character key); the "*" whole file */
    fun matches(r: PrefKey, key: String, who: String?, all: Boolean): Boolean {
        if (r.wholeFile) return true
        if (!r.perChar) return key == r.key
        val star = r.key.indexOf('*')
        val rest: String = if (star < 0) { if (!key.startsWith(r.key)) return false; key.removePrefix(r.key) }
            else {
                val head = r.key.substring(0, star); val tail = r.key.substring(star + 1)
                if (!key.startsWith(head)) return false
                val after = key.removePrefix(head)
                val i = after.indexOf(tail); if (i <= 0) return false
                after.substring(i + tail.length)
            }
        // an account-wide per-element key ("mode.<element>") takes any rest; a character's only its own
        if (r.kind == Kind.SETTINGS) return rest.isNotEmpty()
        return if (all) isCharKey(rest) else rest == who
    }

    /** the rule [key] of [file] falls under (the most specific: an exact key first), or null = not listed */
    fun ruleOf(file: String, key: String): PrefKey? {
        val rs = PREFS.filter { it.file == file }
        return rs.firstOrNull { !it.perChar && !it.wholeFile && it.key == key }
            ?: rs.filter { it.perChar && it.kind != null && it.kind != Kind.SETTINGS }.firstOrNull { matches(it, key, null, all = true) }
            ?: rs.filter { it.perChar && it.kind == null }.firstOrNull { matches(it, key, null, all = true) }
            ?: rs.filter { it.perChar && it.kind == Kind.SETTINGS }.firstOrNull { matches(it, key, null, all = true) }
            ?: rs.firstOrNull { it.wholeFile }
    }

    /** the keys of [file] (now holding [keys]) a reset of [kinds] removes, for [who] (or every character with [all]) */
    fun prefKeys(file: String, keys: Set<String>, kinds: Set<Kind>, who: String?, all: Boolean): Set<String> {
        if (file in NEVER_FILES) return emptySet()
        return keys.filter { k ->
            val r = ruleOf(file, k) ?: return@filter false
            val kind = r.kind ?: return@filter false
            if (kind !in kinds) return@filter false
            if (r.perChar && kind != Kind.SETTINGS) matches(r, k, who, all) else true
        }.toSet()
    }

    /** the preference files any reset may touch */
    val FILES: Set<String> get() = PREFS.filter { it.kind != null }.map { it.file }.toSet()
}
