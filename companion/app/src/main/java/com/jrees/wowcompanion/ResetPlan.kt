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
 *                                 smartBuffs, partyBy, radialPins, sradials (+ the legacy sradial it is rebuilt from), castBar,
 *                                 castBarFight (cb14: the tap-to-cast bar's in-combat layer, next to castBar), castBarParty (cb15: its
 *                                 Party mode layer, the user's Option C, 2026-11-20)
 *                        LAYOUTS (per char) dpad (the DS WoW toolbar; back to its default ON, never "not asked": the prompt stays answered),
 *                                 rbm (movable Home, PadMap m178: the toolbar's mirror bit; a reset = Left is the chat again, mirror 0),
 *                                 ctop (movable Home phase 3, PadMap m179: the top-screen combat bar's flag; a reset = off, the band grows)
 *                        NEVER   formSlotsBy, smartModel, cdLearn, petSpellBy, petDeadBy (learned / remembered game facts); dpadZL,
 *                                 srGone, srBy, srMacros, genUse, fix181, keyProbe, aclOurs, aclReset1 (one-time clean-ups / probes: a
 *                                 reset would run them again); faceSwap, faceCd, combatFace (crash recovery / learned, never synced);
 *                                 macroRoom (the Macros room's last tab); attack (legacy shared record); padLayout, startOver, castBarTab,
 *                                 castBarShow (cb14: the add-on's copy of castBarCombat, home LAYOUTS), castBarPShow (cb15: the add-on's
 *                                 copy of castBarPartyShow, home LAYOUTS)
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
 *  settings     SETTINGS lighterMode, lowMemGraphics, vramCap (#56), homeMapFull, homeMapTerrain, indoorBuild, swapScreens, voiceTyping,
 *                        voiceLanguage (#477), minimapYd, minimapIndoorYd, worldMapFog, avoidDanger, chatDefaultInput, theme
 *               NEVER    browserLogin, windowsId (+ windowsIdRan*), gameAccountAsk, gameAccountPick (the sign-in), polestarGuide, polestarOffBoot,
 *                        questTracking* (Polestar), theme_custom (the player's own made theme: Classic comes back, the made one stays),
 *                        swapScreensFails, settingsTab, the migration marks
 *  framegen     SETTINGS enabled, multiplier, flowScale, performance, fpsLimitContainer, fpsLimitAsk   NEVER fpsLimit108, fpsLimitNote
 *  gameui       SETTINGS mode.<element>, sw.pmode, castbar.hide   LAYOUTS (per char) sw.<part>.<who>, hud.<who>, prdy./prdw./prds.<who>,
 *                        mt.<part>.<who>
 *  cast         SETTINGS buttonLayout, abxyLocation
 *  home         SETTINGS pauseOverGame, rankBadges   LAYOUTS (app-wide: Home has no per-character store) rest, questMin, questOverMap, combatGrow,
 *                        swingTimer, castBarSide, castStrip, castBarCombat, castBarPartyShow (Bar in Party mode), clockHearth,
 *                        clockTeleport, clockGold, clockBags, clockAmmo,
 *                        and the wheels' open / pick: radialTap, radialPickA, spellTap, spellPickA, classTapL, classPickAL, classTapR,
 *                        classPickAR, pauseTap, pausePickA; movable Home (SPEC section 9): per char homeLayout.<who> (a reset = Classic
 *                        WRITTEN under that key: the default stays), and ONLY with "All characters" (fix round 3: they belong to no one
 *                        character) homeLayoutDefault (the seed of a first login), homeLayoutSlots (1517's "Mine" slots, unread)
 *                        and homeLayoutHeld (pending names' held edits, kept by name: never one character key's)
 *                        SKILLS (per char) smartPet.<who>, petFollowB.<who> (B: pet recall)
 *               NEVER    castBarAsked, ruling.hide.2026-09-28 (asked once), homeLayoutMigrated (the one-time migration mark), holdTalk.<who>, talkSendNow.<who> (chat: not named in the
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
 * or (#56 G2) 12 GB Thor (view distance, particles, spells, shadows: GameConfigWtf.LOW_MEMORY_8GB_SAVED), each key still at DS Forever's value. Nothing
 * else in the game is written (the leave-no-trace rule): the low-memory textures and the start's forced keys have no recorded original.
 *
 * THE GAME SETTINGS DS FOREVER CHANGES (Config.wtf, GameConfigWtf.ensure, at Play only, never while the game runs):
 *  - every start: gxApi, ResampleQuality, GamePadEnable, InputDeviceInterfaceStyle, gxMaximize, gxWindowedResolution (#305), the portal /
 *    agentUID when wrong; the defaults only where missing (CONFIG_DEFAULTS, RenderScale #489); the old defaults once per folder (#293)
 *  - Low-memory graphics: the textures (LOW_MEMORY_GRAPHICS) and, on 8 / 12 GB Thors, LOW_MEMORY_8GB (recorded: Reset gives them back)
 *  - Game account (#47): "Ask each time" removes a marked accountList; "Remembered" (the user's ruling 2026-11-20, A) marks the
 *    learned label "!" in accountList (Settings.gameAccountPick; no recorded original: the game rewrites the list itself; not reset)
 *  - ClientSettings_WORK_OPTIMS_BY_API_MASK (the user's ruling 2026-11-20, B): the server's value "16001,0xe,0x0,0x1ff", launch client
 *    only, only where missing (recorded: GameConfigWtf.WORK_OPTIMS_SAVED; Reset removes it again while it is still our value)
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
            "partyBy", "radialPins", "sradials", "sradial", "castBar", "castBarFight", "castBarParty").map { SvKey(SPELLS, it, Kind.SKILLS, true) }.toTypedArray(),
        SvKey(SPELLS, "dpad", Kind.LAYOUTS, true, default = true),
        SvKey(SPELLS, "rbm", Kind.LAYOUTS, true),                                   // movable Home: the DS toolbar's mirror bit (none = off)
        SvKey(SPELLS, "ctop", Kind.LAYOUTS, true),                                  // movable Home phase 3: the top-screen combat bar (none = off)
        *listOf("lay", "hubSw", "hubLy", "meters", "prdY", "prdS", "prdW").map { SvKey(DB, it, Kind.LAYOUTS, true) }.toTypedArray(),
        SvKey(DB, "bagBinds", Kind.SKILLS, true),
        *listOf("compass", "hideTips", "aggroRings", "lootTop").map { SvKey(DB, it, Kind.SETTINGS, false) }.toTypedArray(),
    )

    /** the keys a reset never touches (the inventory's NEVER rows) */
    val SV_NEVER: Map<String, Set<String>> = mapOf(
        SPELLS to setOf("formSlotsBy", "smartModel", "cdLearn", "petSpellBy", "petDeadBy", "dpadZL", "srGone", "srBy", "srMacros", "genUse",
            "fix181", "keyProbe", "aclOurs", "aclReset1", "faceSwap", "faceCd", "combatFace", "macroRoom", "attack", "padLayout", "startOver",
            "castBarTab", "castBarShow", "castBarPShow"),
        DB to setOf("pqol", "pskip", "npEnemies", "npFriends", "npFriendsOff", "npFriendsSet", "routeWp", "chatTop", "compassCal",
            "compassCam", "layV", "prdYv", "prdCurve", "camProbe", "calProbe", "qtDefaults", "chatStyleSet2", "cursorSoftSet",
            "realmOf", "realmFixBak", "who"),     // Safety k6: the realms each name was seen with, and the orphan keys' fold backups;
                                                  // k10: each character's GUID record (which key its settings live under)
    )

    /** a character key as the add-on writes it ("Name-Realm"; [CharKey]: never a partial "Name-?" / "Name-_" one) */
    fun isCharKey(k: Any): Boolean = CharKey.isKey(k)

    /**
     * the saved-table entries [kinds] reset: account keys always; a per-character key for [who] only, or with [all] for every character
     * the tables hold ([tables]: variable -> the table as kept / in the game file)
     */
    fun svTargets(kinds: Set<Kind>, who: String?, all: Boolean, tables: Map<String, List<Map<Any, Any>>>): List<SvReset.Target> {
        val out = ArrayList<SvReset.Target>()
        // the k6 review (item 3): the orphan keys a realm-less session left for this character ("Name-", "Name-?", "Name-_") go with it
        // (removed, never given the default), so a later fold cannot bring their old values back; only while the name holds settings
        // under this one realm (never a guess between two characters of one name). With [all]: every character's orphans.
        val mine = orphanNameOf(who, tables.values.flatten())
        for (k in SV_KEYS) {
            if (k.kind !in kinds) continue
            if (!k.perChar) { out += SvReset.Target(k.variable, k.top, null, k.default); continue }
            val here = tables[k.variable].orEmpty().flatMap { t -> (t[k.top] as? Map<*, *>)?.keys.orEmpty().filterNotNull() }
            val chars = if (all) here.filter { isCharKey(it) }.map { it as String }.toSortedSet() + listOfNotNull(who)
                else listOfNotNull(who)
            for (c in chars.distinct()) out += SvReset.Target(k.variable, k.top, c, k.default)
            val orphans = here.filter { CharKey.isOrphan(it) && (all || (mine != null && CharKey.nameOf(it) == mine)) }.map { it as String }.distinct()
            for (o in orphans) out += SvReset.Target(k.variable, k.top, o, null)
        }
        return out
    }

    /** [who]'s name when its orphans may be reset with it: the name holds settings in [tables] under [who]'s realm only; else null */
    fun orphanNameOf(who: String?, tables: List<Map<*, *>?>): String? {
        val w = who?.takeIf { isCharKey(it) } ?: return null
        if (!RealmFix.resetTakesOrphans(w)) return null                     // the k6 review round 3 (item 5): the device census decides too
        val name = CharKey.nameOf(w) ?: return null
        val rs = RealmFix.realmsByName(tables)[name].orEmpty()
        return name.takeIf { rs.all { r -> "$name-$r" == w } }
    }

    /** one preference key's rule: an exact key, or a per-character key "<prefix><who>" (with [suffix] after it); [kind] null = never */
    /** [allOnly]: an exact key that belongs to no one character (the device's): reset only with "All characters" (fix round 3) */
    data class PrefKey(val file: String, val key: String, val kind: Kind?, val perChar: Boolean = false, val wholeFile: Boolean = false,
                       val allOnly: Boolean = false)

    private fun exact(file: String, kind: Kind?, vararg keys: String) = keys.map { PrefKey(file, it, kind) }
    private fun device(file: String, kind: Kind, vararg keys: String) = keys.map { PrefKey(file, it, kind, allOnly = true) }
    private fun char(file: String, kind: Kind?, vararg prefixes: String) = prefixes.map { PrefKey(file, it, kind, perChar = true) }

    val PREFS: List<PrefKey> = listOf(
        exact("settings", Kind.SETTINGS, "lighterMode", "lowMemGraphics", "vramCap", "homeMapFull", "homeMapTerrain", "indoorBuild", "swapScreens",
            "voiceTyping", "voiceLanguage", "minimapYd", "minimapIndoorYd", "worldMapFog", "avoidDanger", "chatDefaultInput", "theme"),
        exact("settings", null, "browserLogin", "windowsId", "windowsIdRan", "windowsIdRanAt", "windowsIdRanOk", "gameAccountAsk", "gameAccountPick",
            "polestarGuide", "polestarOffBoot", "questTracking", "questTrackingSafePath", "questTrackingPolestar1112", "questTrailRetire1112",
            "theme_custom", "swapScreensFails", "settingsTab"),
        exact("framegen", Kind.SETTINGS, "enabled", "multiplier", "flowScale", "performance", "fpsLimitContainer", "fpsLimitAsk"),
        exact("framegen", null, "fpsLimit108", "fpsLimitNote"),
        exact("gameui", Kind.SETTINGS, "sw.pmode", "castbar.hide"),
        char("gameui", Kind.SETTINGS, "mode."),
        char("gameui", Kind.LAYOUTS, "sw.*.", "hud.", "prdy.", "prdw.", "prds.", "mt.*."),
        exact("cast", Kind.SETTINGS, "buttonLayout", "abxyLocation"),
        exact("home", Kind.SETTINGS, "pauseOverGame", "rankBadges"),
        exact("home", Kind.LAYOUTS, "rest", "questMin", "questOverMap", "combatGrow", "swingTimer", "castBarSide", "castStrip", "castBarCombat",
            "castBarPartyShow",
            "clockHearth", "clockTeleport", "clockGold", "clockBags", "clockAmmo", "radialTap", "radialPickA", "spellTap", "spellPickA",
            "classTapL", "classPickAL", "classTapR", "classPickAR", "pauseTap", "pausePickA"),
        // fix round 3: the first-login seed and 1517's unread "Mine" slots are the device's, never one character's ("This character" keeps them)
        device("home", Kind.LAYOUTS, HomeLayoutStore.KEY_DEFAULT, HomeLayoutStore.KEY_SLOTS_LEGACY, HomeLayoutStore.KEY_HELD),
        char("home", Kind.LAYOUTS, HomeLayoutStore.KEY_PREFIX),
        char("home", Kind.SKILLS, "smartPet.", "petFollowB."),
        exact("home", null, "castBarAsked", "ruling.hide.2026-09-28", HomeLayoutStore.KEY_MIGRATED),
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
        // the k6 review (item 3): a realm-less session's "<prefix>Name-" is that character's too (prefKeys leaves it while the name has
        // keys under another realm in the file)
        return if (all) isCharKey(rest) || CharKey.isOrphan(rest)
            else rest == who || (who != null && CharKey.isOrphan(rest) && CharKey.nameOf(rest) == CharKey.nameOf(who))
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
        val out = keys.filter { k ->
            val r = ruleOf(file, k) ?: return@filter false
            val kind = r.kind ?: return@filter false
            if (kind !in kinds) return@filter false
            if (r.allOnly && !all) return@filter false
            if (r.perChar && kind != Kind.SETTINGS) matches(r, k, who, all) else true
        }.toSet()
        // one character's reset: its name's orphans only while this file holds that name under its own realm alone (never a guess)
        if (all || who == null || !isCharKey(who)) return out
        val name = CharKey.nameOf(who) ?: return out
        val orphan = { k: String -> k.endsWith("$name-") || k.endsWith("$name-?") || k.endsWith("$name-_") }
        if (out.none(orphan)) return out
        // the k6 review round 3 (item 5): the device census must agree too (a same-name alt's orphans are never reset with this one)
        val other = !RealmFix.resetTakesOrphans(who) || keys.any { k -> val i = k.indexOf("$name-"); i >= 0 && (i == 0 || !k[i - 1].isLetterOrDigit()) &&
            k.substring(i).let { w -> w != who && isCharKey(w) } }
        return if (other) out.filterTo(LinkedHashSet()) { !orphan(it) } else out
    }

    /** the preference files any reset may touch */
    val FILES: Set<String> get() = PREFS.filter { it.kind != null }.map { it.file }.toSet()
}
