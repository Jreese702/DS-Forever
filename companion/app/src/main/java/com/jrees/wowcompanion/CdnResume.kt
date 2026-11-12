package com.jrees.wowcompanion

/**
 * A GAME TABLE GOES ON WHERE IT STOPPED (issue #41, 1.0.16, "Stuck on checking the game art"): on mobile data level3.blizzard.com cut a
 * 135 MB table after 30-40 s ("network error (unexpected end of stream)") and every try fetched it again from byte 0, so it never
 * finished (62 / 135 MB, over and over). GameFiles' CDN download now keeps its .part on a network error and asks the next try for the
 * rest (Range: bytes=<have>-); while a round of tries still adds bytes another round follows ([keepGoing]). An answer is appended only
 * when it is a 206 starting exactly where the part ends and running to the file's end; a 200 starts the file over; anything else is
 * refused. The part stays through every failure except one that proves it wrong ([dropsPart]): a link that drops before the headers
 * (connect timeout, host not found, end of stream) or a host's 503 / 403 / 404 must not throw away the bytes already in hand (review of
 * 5f61774f). The finished file still passes CascHeal's checks before it is used. Pure: the off-device tests drive it.
 */
internal object CdnResume {
    /** rounds of CdnRetry (every host, twice) a download may take while each round still adds bytes */
    const val MAX_ROUNDS = 30

    /** how one answer is used */
    sealed class Use {
        /** a 206 from the part's end: the bytes go on after it; [size] = the whole file's */
        data class Append(val size: Long) : Use()
        /** a 200: the whole file from 0 (a kept part goes); [size] = its length, -1 when not said */
        data class Fresh(val size: Long) : Use()
        /** not usable: [why] is the reason (exactly "HTTP <code>" for a plain refusal, which GameFiles.looseRefusal reads) */
        data class Refuse(val why: String) : Use()
    }

    /** where the next request starts for a kept part of [have] bytes of a file of [size] bytes (-1 = not known yet): 0 = from the start */
    fun rangeFor(have: Long, size: Long): Long = if (have > 0 && (size <= 0 || have < size)) have else 0L

    /** the answer [code] to a request from [from] (0 = no Range sent) for a file of [size] bytes (-1 = not known yet) */
    fun use(code: Int, contentRange: String?, contentLength: Long, from: Long, size: Long): Use = when {
        code == 200 -> Use.Fresh(if (contentLength > 0) contentLength else -1L)
        code == 206 && from > 0 -> {
            val r = AppUpdate.contentRange(contentRange)
            if (r != null && r.first == from && r.third > 0 && r.second == r.third - 1 && (size <= 0 || r.third == size)) Use.Append(r.third)
            else Use.Refuse("a wrong resume answer (HTTP 206, ${contentRange ?: "no Content-Range"} for bytes $from-)")
        }
        else -> Use.Refuse("HTTP $code")
    }

    /**
     * Does this try's failure throw the kept part away? Only when the answer proves the part wrong: a wrong resume answer, HTTP 416 (the
     * range is past the file), or a finished file that fails CascHeal ([damaged]). A network error ([refusal] null: an exception, before or
     * during the body) and a plain refusal (5xx / 403 / 404 from one host) keep it for the next try.
     */
    fun dropsPart(refusal: String?, damaged: Boolean = false): Boolean =
        damaged || refusal == "HTTP 416" || (refusal?.startsWith("a wrong resume answer") == true)

    /** another round after a failed one: only while the last round added bytes ([grew]), at most [MAX_ROUNDS] */
    fun keepGoing(grew: Long, round: Int): Boolean = grew > 0 && round < MAX_ROUNDS
}
