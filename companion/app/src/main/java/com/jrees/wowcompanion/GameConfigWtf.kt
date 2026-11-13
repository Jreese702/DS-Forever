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
     * every start while the setting is on; off, the legacy rule below puts the current defaults back.
     */
    val LOW_MEMORY_GRAPHICS = linkedMapOf(
        "worldBaseMip" to "\"2\"",
        "RAIDworldBaseMip" to "\"2\"",
        "graphicsTextureResolution" to "\"0\"",
        "raidGraphicsTextureResolution" to "\"0\"",
        "componentTextureLevel" to "\"1\"",
        "RAIDcomponentTextureLevel" to "\"1\"",
    )

    private val FORCED_CONFIG_KEYS = listOf("gxApi", "RenderScale", "ResampleQuality", "GamePadEnable", "InputDeviceInterfaceStyle")

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

    /**
     * [root]'s Config.wtf and .flavor.info for [product] (its .build.info row [row]): a new file gets every default; an existing one
     * gets the forced keys, the old defaults' new values and the missing keys. For the launch client a beta portal ("test") or another
     * client's agentUID is set right too (a _forever_ folder made from a beta copy would otherwise sign in to the closed beta).
     */
    fun ensure(root: File, product: WowProduct, row: Map<String, String>?, region: String? = null, lowMemory: Boolean = false,
               askAccount: Boolean = false): List<String> {
        // [region]: the player's choice (GameRegion; 2026-11-07, an EU account on a US-set device): it replaces any portal but the beta's
        // [lowMemory]: Settings.lowMemGraphics - the smaller textures over the defaults
        // [askAccount]: Settings.gameAccountAsk (#47) - the game's remembered game-account choice cleared ([forgetAccountChoice]);
        // returns the NAMES of the keys cleared (never a value: a value is an account name)
        val defaults = configDefaults(product, row, if (product.portal == null) region else null).let { d ->
            if (lowMemory) LinkedHashMap(d).apply { putAll(LOW_MEMORY_GRAPHICS) } else d
        }
        val flavorDir = File(root, product.flavorDir)
        flavorDir.mkdirs()
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            WowSwitch.replace(flavorInfo, "Product Flavor!STRING:0\n${product.code}\n".toByteArray())
        }
        val configWtf = File(flavorDir, "WTF/Config.wtf")
        if (!configWtf.exists()) {
            WowSwitch.replace(configWtf, (defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n").toByteArray())
            return emptyList()                    // a new file remembers no account
        }
        val otherAgents = WowProduct.values().filter { it != product }.map { "\"${it.code}\"" }
        val betaPortal = WowProduct.values().mapNotNull { it.portal }.map { "\"$it\"" }

        val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
        val (kept, cleared) = if (askAccount) forgetAccountChoice(original) else original to emptyList()
        val existingKeys = mutableSetOf<String>()
        val updated = kept.map { line ->
            val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
            if (parts?.size != 2) return@map line
            val (key, value) = parts
            existingKeys.add(key)
            val forcedKey = FORCED_CONFIG_KEYS.firstOrNull { it.equals(key, ignoreCase = true) }
            val lowKey = if (lowMemory) LOW_MEMORY_GRAPHICS.keys.firstOrNull { it.equals(key, ignoreCase = true) } else null
            when {
                forcedKey != null && value != defaults.getValue(forcedKey) -> "SET $forcedKey ${defaults.getValue(forcedKey)}"
                lowKey != null -> "SET $lowKey ${LOW_MEMORY_GRAPHICS.getValue(lowKey)}"
                LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${defaults.getValue(key)}"
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
        return cleared
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
