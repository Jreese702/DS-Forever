package com.jrees.wowcompanion

/**
 * THE GAME ART RETRIES BY ITSELF (p-build-tables-blocks-keyboard, a player's report 2026-10-14: "often getting an error about build
 * tables ... after a few retries I was in"). A harvest step, or opening the game files, that fails is tried again after [DELAYS_MS]
 * (a CDN hiccup passes); only the last failure is shown, with its real reason. Pure: the tests drive it with their own sleep.
 */
internal object HarvestRetry {
    /** 3 tries in all: about 20 s more on a real failure, before the art says so */
    val DELAYS_MS = longArrayOf(5_000L, 15_000L)

    fun <T> run(
        delays: LongArray = DELAYS_MS,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        onRetry: (attempt: Int, delayMs: Long, why: String) -> Unit = { _, _, _ -> },
        block: () -> T,
    ): Result<T> {
        var attempt = 0
        while (true) {
            val r = try { Result.success(block()) } catch (e: InterruptedException) { throw e } catch (e: Exception) { Result.failure(e) }
            if (r.isSuccess || attempt >= delays.size) return r
            val d = delays[attempt]
            attempt++
            onRetry(attempt, d, r.exceptionOrNull()?.message ?: "unknown reason")
            sleep(d)
        }
    }
}
