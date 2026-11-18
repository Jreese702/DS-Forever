package com.jrees.wowcompanion

import java.io.File

/**
 * THE GAME'S Config.wtf AND .flavor.info FOR THE CLIENT THE FOLDER RUNS (moved out of the launcher's WoWForeverScreen at the launch switch,
 * 2026-10-21, so it is tested off-device). For the launch client (wowf, _forever_): portal = the install's region (.build.info Branch,
 * "us" by default) and agentUID "wowf"; the beta's "test" portal and its agentUID are never carried into a launch folder. No credential
 * is ever written here (sign-in is NativeLogin's login.txt). Both files are replaced whole (temp, then rename).
 */
object GameConfigWtf {
    /** Config.wtf's defaults for [product]: its portal (the beta's "test", else the install's region) and agentUID, then the rest */
    fun configDefaults(product: WowProduct, row: Map<String, String>?, region: String? = null): Map<String, String> =
        linkedMapOf("portal" to "\"${portalOf(product, row, region)}\"", "agentUID" to "\"${product.code}\"") + CONFIG_DEFAULTS

    /** the portal for [product]: the beta's own, else the login portal of the region (the choice, else the install's Branch; Taiwan -> kr) */
    private fun portalOf(product: WowProduct, row: Map<String, String>?, region: String?): String =
        product.portal ?: GameRegion.portal(region ?: product.portalFor(row))

    private val CONFIG_DEFAULTS = linkedMapOf(
        "gxApi" to "\"D3D11\"",
        "textLocale" to "\"enUS\"",
        "audioLocale" to "\"enUS\"",
        "gxMaximize" to "\"1\"",
        "gxWindowedResolution" to "\"1920x1080\"",
        "graphicsQuality" to "\"0\"",
        "ResampleQuality" to "\"0\"",
        "RenderScale" to "\"1\"",
        "farclip" to "\"1200\"",
        "horizonClip" to "\"1200\"",
        "RAIDfarclip" to "\"1200\"",
        "RAIDhorizonClip" to "\"1200\"",
        "shadowMode" to "\"0\"",
        "graphicsShadowQuality" to "\"0\"",
        "raidGraphicsShadowQuality" to "\"0\"",
        "worldBaseMip" to "\"0\"",
        "RAIDworldBaseMip" to "\"0\"",
        "graphicsTextureResolution" to "\"2\"",
        "raidGraphicsTextureResolution" to "\"2\"",
        "componentTextureLevel" to "\"0\"",
        "RAIDcomponentTextureLevel" to "\"0\"",
        "entityShadowFadeScale" to "\"0\"",
        "refraction" to "\"0\"",
        "groundEffectDensity" to "\"16\"",
        "GamePadEnable" to "\"1\"",
        "InputDeviceInterfaceStyle" to "\"1\"",
    )

    /**
     * LOW-MEMORY GRAPHICS (Settings.lowMemGraphics): the textures at the older, smaller defaults (the LEGACY values: the game's own
     * texture memory sits in the Thor's shared RAM, and the 8 / 12 GB Thors were being killed for memory while the game ran). Written at
     * every start while the setting is on; off, the keys still at these values go back to the current defaults once ([LOW_MEMORY_ON]).
     */
    val LOW_MEMORY_GRAPHICS = linkedMapOf(
        "worldBaseMip" to "\"2\"",
        "RAIDworldBaseMip" to "\"2\"",
        "graphicsTextureResolution" to "\"0\"",
        "raidGraphicsTextureResolution" to "\"0\"",
        "componentTextureLevel" to "\"1\"",
        "RAIDcomponentTextureLevel" to "\"1\"",
    )

    /**
     * LOW-MEMORY GRAPHICS ON THE 8 GB THORS (test build 1489, the user's pick 2026-11-18, #56: "Low-memory graphics on 8 GB Thors ONLY
     * also lowers view distance, particle/spell density and shadows on top of today's texture settings ... only while the player's
     * Low-memory graphics is on, and a player's own saved values for those keys must not be overwritten when they turn it off again"):
     * written at every start while both hold ([ensure]'s lowMemory8). The keys and values are the PC client's (graphics menu cvars and
     * the ones under them); NOT yet confirmed on this client (a device check owed: the game keeps a value it does not know as it is).
     * What each key held before the first such start is kept beside Config.wtf ([LOW_MEMORY_8GB_SAVED]); at the first start without
     * them, every key still at our value gets exactly that back (a key that was not in the file goes again), a key the player changed
     * in the game since stays theirs, and the record goes.
     */
    val LOW_MEMORY_8GB = linkedMapOf(
        "graphicsViewDistance" to "\"1\"",
        "raidGraphicsViewDistance" to "\"1\"",
        "farclip" to "\"800\"",
        "horizonClip" to "\"800\"",
        "RAIDfarclip" to "\"800\"",
        "RAIDhorizonClip" to "\"800\"",
        "graphicsParticleDensity" to "\"1\"",
        "raidGraphicsParticleDensity" to "\"1\"",
        "particleDensity" to "\"10\"",
        "graphicsSpellDensity" to "\"0\"",
        "raidGraphicsSpellDensity" to "\"0\"",
        "graphicsShadowQuality" to "\"0\"",
        "raidGraphicsShadowQuality" to "\"0\"",
        "shadowMode" to "\"0\"",
    )

    /** what [LOW_MEMORY_8GB]'s keys held before DS Forever lowered them (WTF/, beside Config.wtf): "key<TAB>value", [ABSENT] = no line */
    const val LOW_MEMORY_8GB_SAVED = ".dsf-low-memory-8gb-saved"
    private const val ABSENT = "-"

    /** the record's text for [saved] (pure: the tests) */
    internal fun savedText(saved: Map<String, String?>): String = saved.entries.joinToString("") { (k, v) -> "$k\t${v ?: ABSENT}\n" }
    /** [text] back as the record (pure: the tests); a line it cannot read is left out */
    internal fun savedOf(text: String): LinkedHashMap<String, String?> = LinkedHashMap<String, String?>().apply {
        for (l in text.lines()) {
            val i = l.indexOf('\t'); if (i <= 0) continue
            val v = l.substring(i + 1); put(l.substring(0, i), v.takeIf { it != ABSENT })
        }
    }
    private fun low8Key(key: String): String? = LOW_MEMORY_8GB.keys.firstOrNull { it.equals(key, ignoreCase = true) }

    /**
     * Rewritten at every start whatever the folder holds. #305 (1.0.32: "controls not working on bottom screen"; its pad said the game's
     * screen was 1542x1080 on the 1920x1080 top screen, so TapBridge refused every tap): the user's pick 2026-11-16, "force the game to
     * fill the top screen at EVERY start" - gxMaximize 1 and gxWindowedResolution the game screen's size ([ensure]'s screen). Both were
     * written only when missing before, so a folder's own windowed size stayed.
     * #489 (1.0.33: "Resolution Scaling setting not being saved"): RenderScale is NOT forced any more - the user, 2026-11-18: "only set it
     * once / only when the player hasn't chosen, never overwrite the player's own value". It is still a default ([CONFIG_DEFAULTS]): a
     * new Config.wtf gets it and a file without the line gets it added; a value the player picked in the game's options stays.
     */
    private val FORCED_CONFIG_KEYS = listOf("gxApi", "ResampleQuality", "GamePadEnable", "InputDeviceInterfaceStyle",
        "gxMaximize", "gxWindowedResolution")

    /**
     * THE OLD DEFAULTS, put right ONCE PER GAME FOLDER (#293, 1.0.32: settings that do not stay; the user's pick 2026-11-16: the rewrite
     * "runs ONCE per game folder (a marker), never again, so a player's own in-game pick sticks"). Until then it ran at every start and
     * turned back a player's own pick that equals an old value (Texture Resolution Low = graphicsTextureResolution 0, farclip 3000). The
     * marker [LEGACY_DONE] sits beside Config.wtf (as SvKeep's carry-over marker), so a copied folder keeps it; a new Config.wtf gets it
     * at once (it holds the current defaults: nothing to put right).
     */
    private val LEGACY_CONFIG_VALUES = mapOf(
        "farclip" to "\"3000\"",
        "horizonClip" to "\"3000\"",
        "RAIDfarclip" to "\"3000\"",
        "RAIDhorizonClip" to "\"3000\"",
        "worldBaseMip" to "\"2\"",
        "RAIDworldBaseMip" to "\"2\"",
        "graphicsTextureResolution" to "\"0\"",
        "raidGraphicsTextureResolution" to "\"0\"",
        "componentTextureLevel" to "\"1\"",
        "RAIDcomponentTextureLevel" to "\"1\"",
    )

    /** #293: the legacy values' rewrite has run in this folder (WTF/, beside Config.wtf) */
    const val LEGACY_DONE = ".dsf-legacy-values-done"

    /**
     * #293: DS Forever's low-memory textures are in this folder's Config.wtf (there while the setting is on). With the legacy rewrite run
     * once only, the setting's Off needs its own word: the keys still at the low-memory values go back to the defaults once, at the
     * first start with the setting off, and the marker goes. A player's own pick after that is never turned back.
     */
    const val LOW_MEMORY_ON = ".dsf-low-memory-textures"

    /** a game screen's size as Config.wtf's gxWindowedResolution wants it ("1920x1080", landscape), null when unusable. Pure */
    fun screenSize(w: Int, h: Int): String? = if (w <= 0 || h <= 0) null else "${maxOf(w, h)}x${minOf(w, h)}"

    /**
     * [root]'s Config.wtf and .flavor.info for [product] (its .build.info row [row]): a new file gets every default; an existing one
     * gets the forced keys, the old defaults' new values (once per folder: #293) and the missing keys. For the launch client a beta
     * portal ("test") or another client's agentUID is set right too (a _forever_ folder made from a beta copy would otherwise sign in to
     * the closed beta).
     */
    fun ensure(root: File, product: WowProduct, row: Map<String, String>?, region: String? = null, lowMemory: Boolean = false,
               askAccount: Boolean = false, screen: String? = null, lowMemory8: Boolean = false): List<String> {
        // [lowMemory8]: the Thor is an 8 GB model (DeviceTier.eightGb): with [lowMemory] on, [LOW_MEMORY_8GB] too
        // [region]: the player's choice (GameRegion; 2026-11-07, an EU account on a US-set device): it replaces any portal but the beta's
        // [lowMemory]: Settings.lowMemGraphics - the smaller textures over the defaults
        // [askAccount]: Settings.gameAccountAsk (#47) - the game's remembered game-account choice cleared ([forgetAccountChoice]);
        // returns the NAMES of the keys cleared (never a value: a value is an account name)
        // [screen]: #305, the size of the screen the game shows on ([screenSize]: the Thor's top screen, the game's own with Swap
        // screens), its gxWindowedResolution at every start; null or not "WxH" = 1920x1080
        val size = screen?.takeIf { SIZE.matches(it) }
        val low8 = lowMemory && lowMemory8
        val baseDefaults = configDefaults(product, row, if (product.portal == null) region else null)
        val defaults = baseDefaults.let { d ->
            if (lowMemory || size != null) LinkedHashMap(d).apply {
                if (lowMemory) putAll(LOW_MEMORY_GRAPHICS)
                if (low8) putAll(LOW_MEMORY_8GB)
                if (size != null) put("gxWindowedResolution", "\"$size\"")
            } else d
        }
        val flavorDir = File(root, product.flavorDir)
        flavorDir.mkdirs()
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            WowSwitch.replace(flavorInfo, "Product Flavor!STRING:0\n${product.code}\n".toByteArray())
        }
        val configWtf = File(flavorDir, "WTF/Config.wtf")
        val legacyDone = File(configWtf.parentFile, LEGACY_DONE)
        val lowOn = File(configWtf.parentFile, LOW_MEMORY_ON)
        val saved8File = File(configWtf.parentFile, LOW_MEMORY_8GB_SAVED)
        if (!configWtf.exists()) {
            // a new file's own values were the defaults (a key with none: no line)
            if (low8) {
                configWtf.parentFile?.mkdirs()
                WowSwitch.replace(saved8File, savedText(LOW_MEMORY_8GB.keys.associateWith { baseDefaults[it] }).toByteArray())
            }
            WowSwitch.replace(configWtf, (defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n").toByteArray())
            markers(legacyDone, lowOn, lowMemory)
            return emptyList()                    // a new file remembers no account
        }
        val legacyDue = !legacyDone.exists()
        val lowOff = !lowMemory && lowOn.exists()       // the setting turned off since a start that wrote the smaller textures
        val otherAgents = WowProduct.values().filter { it != product }.map { "\"${it.code}\"" }
        val betaPortal = WowProduct.values().mapNotNull { it.portal }.map { "\"$it\"" }

        val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
        val (kept, cleared) = if (askAccount) forgetAccountChoice(original) else original to emptyList()
        // test build 1489: the 8 GB keys' own values, kept once before the first start that lowers them; given back once it is off
        val saved8 = if (saved8File.exists()) runCatching { savedOf(saved8File.readText()) }.getOrNull() ?: LinkedHashMap() else null
        val restore8 = !low8 && saved8 != null
        if (low8) {
            val now = LinkedHashMap<String, String>()
            for (line in kept) {
                val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
                if (parts?.size == 2) low8Key(parts[0])?.let { k -> if (k !in now) now[k] = parts[1] }
            }
            val rec = saved8 ?: LinkedHashMap()
            var grew = false
            for (k in LOW_MEMORY_8GB.keys) if (!rec.containsKey(k)) {
                // the value this start would have left without the 8 GB keys (the old defaults' fix included)
                rec[k] = now[k]?.let { if (legacyDue && LEGACY_CONFIG_VALUES[k] == it) baseDefaults[k] else it } ?: baseDefaults[k]
                grew = true
            }
            // before Config.wtf: a stop between the two finds the record, never our values without one
            if (grew || saved8 == null) WowSwitch.replace(saved8File, savedText(rec).toByteArray())
        }
        val existingKeys = mutableSetOf<String>()
        val updated = kept.mapNotNull { line ->
            val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
            if (parts?.size != 2) return@mapNotNull line
            val (key, value) = parts
            existingKeys.add(key)
            val forcedKey = FORCED_CONFIG_KEYS.firstOrNull { it.equals(key, ignoreCase = true) }
            val lowKey = if (lowMemory) LOW_MEMORY_GRAPHICS.keys.firstOrNull { it.equals(key, ignoreCase = true) } else null
            val key8 = low8Key(key)
            when {
                forcedKey != null && value != defaults.getValue(forcedKey) -> "SET $forcedKey ${defaults.getValue(forcedKey)}"
                lowKey != null -> "SET $lowKey ${LOW_MEMORY_GRAPHICS.getValue(lowKey)}"
                low8 && key8 != null -> "SET $key8 ${LOW_MEMORY_8GB.getValue(key8)}"
                // off again: still at our value = the player's own value back exactly (no line before = none now); changed in game = theirs
                restore8 && key8 != null && saved8!!.containsKey(key8) && value == LOW_MEMORY_8GB.getValue(key8) ->
                    saved8[key8]?.let { "SET $key8 $it" }
                legacyDue && LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${defaults.getValue(key)}"
                lowOff && LOW_MEMORY_GRAPHICS[key] == value -> "SET $key ${defaults.getValue(key)}"
                key.equals("portal", ignoreCase = true) && product.portal == null && value in betaPortal -> "SET portal ${defaults.getValue("portal")}"
                key.equals("portal", ignoreCase = true) && product.portal == null && region != null &&
                    !value.trim('"').equals(GameRegion.portal(region), ignoreCase = true) -> "SET portal ${defaults.getValue("portal")}"
                // a "tw" portal does not exist (the user, 2026-11-11: regions fix): Taiwan signs in through kr, choice or not
                key.equals("portal", ignoreCase = true) && product.portal == null &&
                    value.trim('"').lowercase() in GameRegion.DEAD_PORTALS -> "SET portal ${defaults.getValue("portal")}"
                key.equals("agentUID", ignoreCase = true) && value in otherAgents -> "SET agentUID ${defaults.getValue("agentUID")}"
                else -> line
            }
        } + defaults.filterKeys { it !in existingKeys }.map { (k, v) -> "SET $k $v" }

        if (updated != original) {
            WowSwitch.replace(configWtf, (updated.joinToString("\n") + "\n").toByteArray())
        }
        if (restore8) runCatching { saved8File.delete() }          // after Config.wtf: a stop between the two gives them back once more
        markers(legacyDone, lowOn, lowMemory)             // after Config.wtf: a stop between the two only runs the rewrite once more
        return cleared
    }

    private val SIZE = Regex("[1-9][0-9]{2,4}x[1-9][0-9]{2,4}")

    /** #293's two markers as this start leaves Config.wtf; a marker that cannot be written never stops Play */
    private fun markers(legacyDone: File, lowOn: File, lowMemory: Boolean) {
        runCatching { if (!legacyDone.exists()) legacyDone.writeText("") }
        runCatching { if (lowMemory) { if (!lowOn.exists()) lowOn.writeText("") } else if (lowOn.exists()) lowOn.delete() }
    }

    /**
     * THE GAME'S REMEMBERED GAME ACCOUNT (GitHub #47): with several game accounts on one Battle.net login (WoW1, WoW2 ...), the client
     * keeps the last pick in Config.wtf and goes straight in with it. The keys, from the PC client's known Config.wtf (NOT yet confirmed
     * on this client: a device check on a multi-account login is owed):
     *  - accountList: the game accounts, "|"-separated, the remembered one marked with a leading "!" (e.g. "WoW1|!WoW2|"). Only a list
     *    that HOLDS a "!" is a choice; that line goes (the game writes it again from Battle.net), a list with no marker stays.
     *  - NOT accountName (review 11-13): on the Battle.net-era clients that is the remembered Battle.net login (the e-mail), not the
     *    game-account pick; clearing it would forget the player's login name and is no part of the choice. It is never touched.
     */
    val ACCOUNT_CHOICE_KEYS = listOf("accountList")

    /**
     * [lines] without the remembered game-account choice, and the NAMES of the keys cleared (in [ACCOUNT_CHOICE_KEYS]'s spelling; each
     * once). No such key, or none that remembers anything = the same lines and no names (a no-op). Pure; never returns a value.
     */
    fun forgetAccountChoice(lines: List<String>): Pair<List<String>, List<String>> {
        val cleared = LinkedHashSet<String>()
        val kept = lines.filter { line ->
            val parts = line.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2)
            val key = parts?.getOrNull(0)?.let { k -> ACCOUNT_CHOICE_KEYS.firstOrNull { it.equals(k, ignoreCase = true) } }
                ?: return@filter true
            val value = parts.getOrNull(1)?.trim()?.trim('"').orEmpty()
            val remembers = value.split('|').any { it.trim().startsWith("!") }      // accountList: only a marked list is a choice
            if (remembers) cleared.add(key)
            !remembers
        }
        return (if (cleared.isEmpty()) lines else kept) to cleared.toList()
    }

    /**
     * THE REMEMBERED GAME ACCOUNT FOR THE PC (the user's pick 2026-11-17, "Yes, remember it": the PC remembers it the same way the
     * Thor does): [lines]' accountList value when it remembers a pick (holds a "!"), without its quotes; null = none, or nothing
     * remembered. Streaming hands it to DS Forever Desktop (login_file's account_list) only with "Game account: Remembered"; it is
     * an account name, never logged. Pure.
     */
    fun rememberedAccountList(lines: List<String>): String? = lines.mapNotNull { line ->
        val parts = line.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2)
        if (parts?.size != 2 || ACCOUNT_CHOICE_KEYS.none { it.equals(parts[0], ignoreCase = true) }) null
        else parts[1].trim().trim('"').takeIf { v -> v.split('|').any { it.trim().startsWith("!") } }
    }.lastOrNull()?.takeIf { it.length <= 512 && it.none { c -> c == '"' || c == '\r' || c == '\n' } }

    /**
     * THE THOR'S UI SCALE FOR THE PC'S STREAM (the user's ruling 2026-11-18: "while streaming, Desktop also sets WoW's UI scale to match
     * the Thor's and puts the PC's own back after the session"): the game's own UI scale keys in [lines] ([UI_SCALE_KEYS], the
     * last line of each, in that spelling), plain numbers only; null when Config.wtf has neither (the game's default: the PC is left
     * alone). Sent on the bridge's hello. Pure
     */
    fun uiScale(lines: List<String>): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            val parts = line.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2) ?: continue
            if (parts.size != 2) continue
            val key = UI_SCALE_KEYS.firstOrNull { it.equals(parts[0], ignoreCase = true) } ?: continue
            val v = parts[1].trim().trim('"').trim()
            if (v.length <= 16 && UI_SCALE_VALUE.matches(v)) out[key] = v
        }
        return out.takeIf { it.isNotEmpty() }
    }
    /** WoW's UI scale CVars (useUiScale turns the slider on, uiScale is its value) */
    val UI_SCALE_KEYS = listOf("useUiScale", "uiScale")
    private val UI_SCALE_VALUE = Regex("^[0-9]+(\\.[0-9]+)?$")

    /** [uiScale] of [root]'s Config.wtf for the client it runs ([WowProduct.of]); null when unreadable or none */
    fun uiScale(root: File): Map<String, String>? = runCatching {
        File(File(root, WowProduct.of(root).flavorDir), "WTF/Config.wtf").takeIf { it.isFile }?.readLines()?.let(::uiScale)
    }.getOrNull()

    /** [rememberedAccountList] of [root]'s Config.wtf for the client it runs ([WowProduct.of]); null when unreadable */
    fun rememberedAccountList(root: File): String? = runCatching {
        File(File(root, WowProduct.of(root).flavorDir), "WTF/Config.wtf").takeIf { it.isFile }?.readLines()?.let(::rememberedAccountList)
    }.getOrNull()

    /**
     * THE REPORT'S Config.wtf LINE (issue #57, 1.0.17: "no log in fields at sign in screen in game", a 47.7 GB folder named "World of
     * Warcraft" copied whole; the client sat at 0 bytes with its window up and never read login.txt - the cause is not known). It
     * says which keys the folder brings that DS Forever never sets itself, by NAME only (a value may be an account name), plus the portal
     * and the number of SET lines, so the next report can tell a copied PC setting apart. Pure.
     */
    fun reportLine(text: String?): String {
        if (text == null) return "missing"
        val keys = text.lineSequence().mapNotNull { l ->
            l.trim().takeIf { it.startsWith("SET ", ignoreCase = true) }?.drop(4)?.trim()?.split(" ", limit = 2)?.takeIf { it.size == 2 }
        }.toList()
        val ours = (CONFIG_DEFAULTS.keys + LOW_MEMORY_GRAPHICS.keys + listOf("portal", "agentUID")).map { it.lowercase() }.toSet()
        val portal = keys.lastOrNull { it[0].equals("portal", ignoreCase = true) }?.get(1)?.trim('"') ?: "(none)"
        val others = keys.map { it[0] }.filter { it.lowercase() !in ours }.distinct()
        return "${keys.size} SET lines; portal $portal; not set by DS Forever: " +
            if (others.isEmpty()) "none" else others.take(REPORT_KEYS).joinToString(" ") + if (others.size > REPORT_KEYS) " (+${others.size - REPORT_KEYS})" else ""
    }
    private const val REPORT_KEYS = 60
}
