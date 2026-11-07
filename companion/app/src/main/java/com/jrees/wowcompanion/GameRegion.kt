package com.jrees.wowcompanion

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * THE PLAYER'S REGION (the user, 2026-11-07: "When starting the app we need to have a region select prompt with the login credentials
 * ... to let people from EU log in to their region"; picked "On the login prompt", US / EU / Korea / Taiwan). A Battle.net account
 * belongs to ONE region and the game signs in to the region its Config.wtf names (SET portal). The install guessed it from the
 * device's country (WowClientDownloader.directBranch) and nothing could change it after: an EU account on a US-set Thor could never
 * sign in. The choice is kept here (absent = no choice made: the install's own region stands) and GameConfigWtf.ensure writes it
 * as the portal before every start.
 */
object GameRegion {
    data class Region(val code: String, val label: String)

    /** the game's portals, in the prompt's order */
    val ALL = listOf(Region("us", "US"), Region("eu", "EU"), Region("kr", "Korea"), Region("tw", "Taiwan"))

    private const val PREFS = "dsf_region"
    private const val KEY = "region"

    /** the region the player chose, or null when none was chosen (the install's own region stands) */
    fun chosen(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { c -> ALL.any { it.code == c } }

    fun choose(ctx: Context, code: String) {
        if (ALL.none { it.code == code }) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
    }

    /** what the prompt shows first: the choice, else the game folder's own portal (Config.wtf), else the device's country's region */
    fun current(ctx: Context, root: File? = null): String {
        chosen(ctx)?.let { return it }
        root?.let { portalOf(it) }?.let { return it }
        return guess(Locale.getDefault().country)
    }

    /** the portal a game folder's Config.wtf names (lowercase), null when there is none */
    fun portalOf(root: File): String? = runCatching {
        val f = WowProduct.values().map { File(File(root, it.flavorDir), "WTF/Config.wtf") }.firstOrNull { it.isFile } ?: return null
        f.readLines().firstNotNullOfOrNull { line ->
            Regex("""^SET\s+portal\s+"?([A-Za-z]+)"?""", RegexOption.IGNORE_CASE).find(line.trim())?.groupValues?.get(1)?.lowercase()
        }?.takeIf { p -> ALL.any { it.code == p } }
    }.getOrNull()

    private val EU = setOf("AT", "BE", "BG", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HR", "HU", "IE", "IS", "IT",
        "LT", "LU", "LV", "MT", "NL", "NO", "PL", "PT", "RO", "RU", "SE", "SI", "SK", "UA", "TR")

    /** the install's own rule (WowClientDownloader.directBranch): the region of a country */
    fun guess(country: String): String = when (country.uppercase(Locale.ROOT)) {
        "KR" -> "kr"
        "TW", "HK" -> "tw"
        in EU -> "eu"
        else -> "us"
    }
}
