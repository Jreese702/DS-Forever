package com.jrees.wowcompanion

import java.security.cert.CertificateException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * BLIZZARD OVER HTTPS (2026-11-20; ported from WoW Forever for Android v2.3.7 7d0b00d0 and v2.3.9 efa6e700 by Jared (jaredgei), GPL-3.0):
 * the patch server and the CDN are read over HTTPS only, the same for the game's updater, a direct install and the art.
 *
 * - The patch server is Blizzard's HTTPS version service ([PATCH_SERVERS]: us first, eu if it does not answer). Both list every
 *   region's build, like the plain-HTTP us.patch.battle.net:1119 they replace. There is NO plain-HTTP fallback: the cdns table names
 *   the CDN servers, so an unauthenticated one could send every download to a host of anyone's choosing.
 * - CDN servers are the cdns row's https "Servers" (or .build.info's "CDN Servers"), else its "Hosts" over HTTPS ([servers]).
 *   Level 3 serves HTTPS under its own name (level3.ssl.blizzard.com). Only a loopback "127.0.0.1:<port>" host (the tests' local
 *   server) is read over plain HTTP.
 * - A server whose CERTIFICATE fails (Korea's blizzard.gcdn.cloudn.co.kr: an expired self-signed one) is skipped for [SKIP_MS]
 *   ([note], [trusted]); the other servers mirror it. The skip ends on its own, so a hotel Wi-Fi's sign-in page (which fails every
 *   certificate until the player signs in) never leaves the CDN unusable until a restart. Certificate checks are never turned off,
 *   for it or anything else, and a plain network error (a reset handshake on a weak link) never marks a server.
 */
object CdnHttps {
    /** the HTTPS version service of a product, base URLs in the order tried ("<base>/<product>/versions", ".../cdns") */
    val PATCH_SERVERS = listOf("https://us.version.battle.net/v2/products", "https://eu.version.battle.net/v2/products")

    /** the built-in CDN hosts when neither the patch server nor .build.info names any */
    val DEFAULT_HOSTS = listOf("level3.blizzard.com", "us.cdn.blizzard.com")

    /** a CDN host's HTTPS name (Level 3's plain host has an HTTPS twin) */
    fun httpsHost(host: String): String = if (host.equals("level3.blizzard.com", ignoreCase = true)) "level3.ssl.blizzard.com" else host

    /**
     * pure: the HTTPS base URLs ("https://us.cdn.blizzard.com", no path, no options) among [servers] (the cdns row's Servers /
     * .build.info's CDN Servers, e.g. "https://level3.ssl.blizzard.com/?maxhosts=4"), else [hosts] over HTTPS, else [DEFAULT_HOSTS].
     * Never a plain-HTTP one.
     */
    fun servers(servers: List<String>, hosts: List<String>): List<String> {
        val https = servers.map { it.trim() }.filter { it.startsWith("https://", ignoreCase = true) }
            .map { "https://" + it.substring(8).substringBefore('?').substringBefore('/').lowercase(Locale.ROOT) }
            .filter { it.length > 8 }
        if (https.isNotEmpty()) return https.distinct()
        return hosts.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_HOSTS }.map(::base).distinct()
    }

    /** the tests' local CDN ("127.0.0.1:<port>", plain HTTP like the Desktop's test listener); never a Blizzard host */
    private val LOOPBACK = Regex("""127\.0\.0\.1:\d{1,5}""")

    private fun base(host: String) = if (LOOPBACK.matches(host)) "http://$host" else "https://" + httpsHost(host).lowercase(Locale.ROOT)

    /** [servers] from the space-separated columns of .build.info / the cdns table (either may be null or blank) */
    fun servers(serversColumn: String?, hostsColumn: String?): List<String> =
        servers(split(serversColumn), split(hostsColumn))

    private fun split(column: String?) = column.orEmpty().split(' ').filter { it.isNotBlank() }

    /** how long a server whose certificate failed is skipped */
    const val SKIP_MS = 10 * 60_000L

    /** host -> when its certificate failed */
    private val untrusted = ConcurrentHashMap<String, Long>()

    /** for tests: the clock the skips run on */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private fun hostOf(server: String) = server.substringAfter("://").substringBefore('/').substringBefore('?').lowercase(Locale.ROOT)

    /** pure: [e] (or a cause of it) is a refused certificate or name, not a network hiccup */
    fun isCertificateFailure(e: Throwable?): Boolean {
        var t = e
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is SSLPeerUnverifiedException || t is CertificateException || t is java.security.cert.CertPathValidatorException) return true
            t = t.cause
        }
        return false
    }

    /** a fetch from [server] failed with [e]: a certificate failure skips that server for [SKIP_MS]. true = it was one */
    fun note(server: String, e: Throwable): Boolean {
        if (!isCertificateFailure(e)) return false
        if (untrusted.put(hostOf(server), clock()) == null) {
            runCatching { android.util.Log.w("WCSETUP", "cdn: ${hostOf(server)} has a certificate that fails its check; skipped for ${SKIP_MS / 60_000} min") }
        }
        return true
    }

    /** [server] has not failed its certificate check in the last [SKIP_MS] */
    fun trusted(server: String): Boolean {
        val host = hostOf(server)
        val at = untrusted[host] ?: return true
        if (clock() - at < SKIP_MS) return false
        untrusted.remove(host, at)
        return true
    }

    /**
     * [servers] without the ones whose certificate failed; all of them when every one has (a Wi-Fi sign-in page fails them all, and
     * the check still runs on each, so asking again is safe)
     */
    fun usable(servers: List<String>): List<String> = servers.filter(::trusted).ifEmpty { servers }

    /** for tests: every server trusted again */
    fun forgetUntrusted() = untrusted.clear()
}
