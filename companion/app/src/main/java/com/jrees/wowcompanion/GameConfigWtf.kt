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
    fun configDefaults(product: WowProduct, row: Map<String, String>?): Map<String, String> =
        linkedMapOf("portal" to "\"${product.portalFor(row)}\"", "agentUID" to "\"${product.code}\"") + CONFIG_DEFAULTS

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
    fun ensure(root: File, product: WowProduct, row: Map<String, String>?) {
        val defaults = configDefaults(product, row)
        val flavorDir = File(root, product.flavorDir)
        flavorDir.mkdirs()
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            WowSwitch.replace(flavorInfo, "Product Flavor!STRING:0\n${product.code}\n".toByteArray())
        }
        val configWtf = File(flavorDir, "WTF/Config.wtf")
        if (!configWtf.exists()) {
            WowSwitch.replace(configWtf, (defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n").toByteArray())
            return
        }
        val otherAgents = WowProduct.values().filter { it != product }.map { "\"${it.code}\"" }
        val betaPortal = WowProduct.values().mapNotNull { it.portal }.map { "\"$it\"" }

        val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
        val existingKeys = mutableSetOf<String>()
        val updated = original.map { line ->
            val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
            if (parts?.size != 2) return@map line
            val (key, value) = parts
            existingKeys.add(key)
            val forcedKey = FORCED_CONFIG_KEYS.firstOrNull { it.equals(key, ignoreCase = true) }
            when {
                forcedKey != null && value != defaults.getValue(forcedKey) -> "SET $forcedKey ${defaults.getValue(forcedKey)}"
                LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${defaults.getValue(key)}"
                key.equals("portal", ignoreCase = true) && product.portal == null && value in betaPortal -> "SET portal ${defaults.getValue("portal")}"
                key.equals("agentUID", ignoreCase = true) && value in otherAgents -> "SET agentUID ${defaults.getValue("agentUID")}"
                else -> line
            }
        } + defaults.filterKeys { it !in existingKeys }.map { (k, v) -> "SET $k $v" }

        if (updated != original) {
            WowSwitch.replace(configWtf, (updated.joinToString("\n") + "\n").toByteArray())
        }
    }
}
