package com.jrees.wowcompanion

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A CDN FETCH THAT SURVIVES A HICCUP (p-build-tables-blocks-keyboard, a player's report 2026-10-14: the game-art table failed now and
 * then and worked "after a few retries"). Every host the game's .build.info names, then all of them once more after a short pause;
 * the reason of the last failure is kept for the message (HTTP 503 from <host>, timed out, no network...). Pure: the tests drive it.
 */
internal object CdnRetry {
    const val PASSES = 2
    const val PAUSE_MS = 1_500L

    /**
     * [attempt] one host: null = done, else why not (it may throw: a network error becomes its reason). Returns null on success,
     * else the last reason with its host. A reason [final] accepts (a 404 for a file the CDN keeps only inside an archive) ends it at
     * once: every host and the second pass would only say the same thing again (issue #7, "art slow": about 6 s per file).
     */
    fun run(hosts: List<String>, passes: Int = PASSES, pause: (Long) -> Unit = { Thread.sleep(it) }, final: (String) -> Boolean = { false },
            attempt: (String) -> String?): String? {
        if (hosts.isEmpty()) return "no CDN hosts in the game's .build.info"
        var why = "no CDN host answered"
        for (p in 0 until passes) {
            if (p > 0) pause(PAUSE_MS * p)
            for (h in hosts) {
                val r = try { attempt(h) } catch (e: Exception) { describe(e) }
                if (r == null) return null
                why = "$r from $h"
                if (final(r)) return why
            }
        }
        return why
    }

    fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "timed out"
        is UnknownHostException -> "no network (host not found)"
        is IOException -> "network error (${e.message ?: e.javaClass.simpleName})"
        else -> e.message ?: e.javaClass.simpleName
    }
}
