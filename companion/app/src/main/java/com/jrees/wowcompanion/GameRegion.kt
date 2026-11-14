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
    private const val KEY_BY = "region_by"
    private const val KEY_AT = "region_at"

    /** the region the player chose, or null when none was chosen (the install's own region stands) */
    fun chosen(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.takeIf { c -> ALL.any { it.code == c } }

    /** where the choice was made: the login prompt's Region row, or the Start menu's Region button (one press steps to the next) */
    const val BY_PROMPT = "the login prompt"
    const val BY_MENU = "the Start menu"

    /**
     * [by]: where it was made ([BY_PROMPT] / [BY_MENU]). The login triage (2026-11-13, #248: a US install on a US-zoned Thor signing in
     * to the EU portal, "i cant get pass choosing experience type"; nothing said who set EU or when): a CHANGE is logged with its source
     * and kept with its time for the report's Region line ([reportLine]). Saving the prompt with the same region changes nothing.
     */
    fun choose(ctx: Context, code: String, by: String = "") {
        if (ALL.none { it.code == code }) return
        val was = chosen(ctx)
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code)
        if (was != code) e.putString(KEY_BY, by).putLong(KEY_AT, System.currentTimeMillis())
        e.apply()
        if (was != code) {
            runCatching { NativeLogin.logEvent("region: ${was ?: "none chosen"} -> $code" + if (by.isNotEmpty()) " ($by)" else "") }
            listeners.forEach { runCatching { it(code) } }
        }
    }

    /** who made the current choice and when (epoch ms; 0 = before this was kept), or null with no choice made */
    fun choiceSource(ctx: Context): Pair<String, Long>? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (chosen(ctx) == null) return null
        return (p.getString(KEY_BY, null) ?: "") to p.getLong(KEY_AT, 0L)
    }

    /**
     * THE REPORT'S REGION LINE (the login triage, #248). An account belongs to ONE region and the game signs in at the portal its
     * Config.wtf names, so the line puts side by side: the portal the game will sign in at ([configPortal], the Config.wtf's; else what the
     * choice or the install's branch makes it), the choice ([chosen], [by], [at] written by [stamp]), the install's .build.info
     * [branch] and the device's [country] (its region by [guess]). When the portal is neither the install's nor the device's region it
     * says so in plain words (a region picked by mistake signs a US account in to EU: an empty account, no realms, BLZ errors).
     * Names and codes only. Pure.
     */
    fun reportLine(chosen: String?, by: String?, at: Long, branch: String?, configPortal: String?, country: String, stamp: (Long) -> String): String {
        val b = branch?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val signsAt = configPortal?.lowercase(Locale.ROOT) ?: (chosen ?: b)?.let { portal(it) }
        val device = guess(country)
        val choice = if (chosen == null) "none (the install's own region stands)"
            else chosen + " (" + listOfNotNull(by?.takeIf { it.isNotEmpty() } ?: "source not kept by an older version", at.takeIf { it > 0L }?.let(stamp)).joinToString(", ") + ")"
        val line = "Region: the game signs in at ${signsAt ?: "?"}; chosen $choice; the install's branch ${b ?: "?"}; " +
            "the device's country ${country.ifEmpty { "?" }} (${device})"
        val matches = { r: String? -> r != null && portal(r) == signsAt }
        return if (signsAt != null && b != null && !matches(b) && !matches(device))
            "$line; DIFFERS: the game signs in at $signsAt while the install and the device say ${if (b == device) b else "$b / $device"}"
        else line
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
