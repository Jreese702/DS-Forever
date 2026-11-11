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
    /**
     * [code]: the region (.build.info Branch, the patch server's versions/cdns row, the build the game downloads); [portal]: the
     * Config.wtf login server. They differ for Taiwan only (the user, 2026-11-11: regions fix): there is no tw.actual.battle.net, so a
     * "tw" portal fails with BLZ51901016; Taiwan signs in through the Korean portal and keeps Taiwan's build (as YoruDev-Ryland's
     * WoWForeverywhere a4ff6b6, GPL-3.0, found and its reporter confirmed).
     */
    data class Region(val code: String, val label: String, val portal: String = code)

    /** the game's regions, in the prompt's order */
    val ALL = listOf(Region("us", "US"), Region("eu", "EU"), Region("kr", "Korea"), Region("tw", "Taiwan", portal = "kr"))

    /** the login portal for the region [code] (Taiwan -> kr; an unknown code as it is, lowercase) */
    fun portal(code: String): String = code.lowercase(Locale.ROOT).let { c -> ALL.firstOrNull { it.code == c }?.portal ?: c }

    /** portals that do not exist (a "tw" portal written before the regions fix): always replaced, even with no choice made */
    val DEAD_PORTALS = setOf("tw")

    /**
     * WHO HEARS A CHANGE (the user, 2026-11-11: regions fix): the launcher re-checks the game's build for the new region at once (regions
     * patch on different days). Called on the thread that chose, after the choice is saved.
     */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    private const val PREFS = "dsf_region"
    private const val KEY = "region"

    /** the region the player chose, or null when none was chosen (the install's own region stands) */
    fun chosen(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { c -> ALL.any { it.code == c } }

    fun choose(ctx: Context, code: String) {
        if (ALL.none { it.code == code }) return
        val was = chosen(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        if (was != code) listeners.forEach { runCatching { it(code) } }
    }

    /** what the prompt shows first: the choice, else the game folder's own region ([regionOf]), else the device's country's region */
    fun current(ctx: Context, root: File? = null): String {
        chosen(ctx)?.let { return it }
        root?.let { regionOf(it) }?.let { return it }
        return guess(Locale.getDefault().country)
    }

    /**
     * The region a game folder is set to with no choice made: its Config.wtf portal, except that a "kr" portal on a Taiwan build
     * (.build.info Branch "tw") is Taiwan (Taiwan signs in through kr), and a folder without a usable portal goes by its Branch.
     */
    fun regionOf(root: File): String? {
        val branch = runCatching { WowProduct.choose(root)?.row?.get("Branch")?.lowercase(Locale.ROOT) }.getOrNull()
            ?.takeIf { b -> ALL.any { it.code == b } }
        val portal = portalOf(root)
        return when {
            portal != null && branch != null && portal(branch) == portal -> branch
            portal != null -> portal
            else -> branch
        }
    }

    /** the portal a game folder's Config.wtf names (lowercase), null when there is none (or a dead one, [DEAD_PORTALS]) */
    fun portalOf(root: File): String? = runCatching {
        val f = WowProduct.values().map { File(File(root, it.flavorDir), "WTF/Config.wtf") }.firstOrNull { it.isFile } ?: return null
        f.readLines().firstNotNullOfOrNull { line ->
            Regex("""^SET\s+portal\s+"?([A-Za-z]+)"?""", RegexOption.IGNORE_CASE).find(line.trim())?.groupValues?.get(1)?.lowercase()
        }?.takeIf { p -> p !in DEAD_PORTALS && ALL.any { it.code == p } }
    }.getOrNull()

    /*
     * THE COUNTRY TABLE, the one copy (the user, 2026-11-11: regions fix; WowClientDownloader's direct install uses it too). Blizzard's
     * Europe region also serves Russia and the CIS, the Middle East and Africa; Macau signs in with Hong Kong and Taiwan (jklock's
     * wolkaraforever 85d938d did the same). Anything not listed stays US (the Americas, Oceania, South-East Asia and the rest), as before.
     */
    private val EU = setOf(
        // the EU, the EEA, the UK and the rest of Europe
        "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FO", "FR", "GB", "GG", "GI", "GL",
        "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL", "PT", "RO",
        "RS", "SE", "SI", "SJ", "SK", "SM", "UA", "VA", "XK",
        // Russia, the Caucasus and Central Asia
        "RU", "AM", "AZ", "GE", "KZ", "KG", "TJ", "TM", "UZ",
        // the Middle East
        "TR", "AE", "BH", "IL", "IQ", "IR", "JO", "KW", "LB", "OM", "PS", "QA", "SA", "SY", "YE",
        // Africa
        "DZ", "AO", "BJ", "BW", "BF", "BI", "CM", "CV", "CF", "TD", "KM", "CG", "CD", "CI", "DJ", "EG", "GQ", "ER", "SZ", "ET", "GA", "GM",
        "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG", "MW", "ML", "MR", "MU", "MA", "MZ", "NA", "NE", "NG", "RE", "RW", "ST", "SN", "SC",
        "SL", "SO", "ZA", "SS", "SD", "TZ", "TG", "TN", "UG", "EH", "YT", "ZM", "ZW",
    )
    private val TW = setOf("TW", "HK", "MO")

    /** the region of a country (ISO 3166 alpha-2; the prompt's first guess and a direct install's region) */
    fun guess(country: String): String = when (country.uppercase(Locale.ROOT)) {
        "KR" -> "kr"
        in TW -> "tw"
        in EU -> "eu"
        else -> "us"
    }
}
