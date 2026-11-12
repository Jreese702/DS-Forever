package app.gamenative

/**
 * A NATIVE CRASH'S TOMBSTONE AS TEXT (issue #114, 1.0.23 "crashed (native code)", and #56: Android 12+ hands the exit record's
 * trace as the tombstone's protobuf, and CrashLogs wrote those bytes as text - the report showed "null pointer dereference",
 * "rumble-poller-2" and "waitForRumble" between binary noise, with no pc, no offset and no frame order, so the crash could not be
 * placed in evshim.c). Decoded here with a small protobuf reader into the familiar "#00 pc ... lib (function+offset)" lines:
 * the signal, the cause, the abort message, the crashing thread's whole backtrace and the other threads' top frames.
 * Field numbers: AOSP system/core/debuggerd/proto/tombstone.proto. Pure: the tests build a tombstone by hand.
 */
object Tombstone {
    private class Field(val no: Int, val wire: Int, val num: Long, val bytes: ByteArray?)

    /** the fields of one message, or null when [b] is not a well-formed protobuf message */
    private fun fields(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long? {
            var r = 0L; var shift = 0
            while (i < b.size && shift < 64) {
                val c = b[i++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        while (i < b.size) {
            val key = varint() ?: return null
            val no = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            if (no <= 0) return null
            when (wire) {
                0 -> out += Field(no, 0, varint() ?: return null, null)
                1 -> { if (i + 8 > b.size) return null; var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; out += Field(no, 1, v, null) }
                2 -> { val n = varint() ?: return null; if (n < 0 || i + n > b.size) return null; out += Field(no, 2, 0, b.copyOfRange(i, i + n.toInt())); i += n.toInt() }
                5 -> { if (i + 4 > b.size) return null; var v = 0L; for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; out += Field(no, 5, v, null) }
                else -> return null
            }
        }
        return out
    }

    private fun List<Field>.str(no: Int) = firstOrNull { it.no == no && it.bytes != null }?.bytes?.toString(Charsets.UTF_8)
    private fun List<Field>.num(no: Int) = firstOrNull { it.no == no && it.bytes == null }?.num
    private fun List<Field>.msgs(no: Int) = filter { it.no == no && it.bytes != null }.mapNotNull { fields(it.bytes!!) }

    private class Thread(val id: Long, val name: String, val frames: List<String>)

    private fun frame(n: Int, f: List<Field>): String {
        val relPc = f.num(1) ?: 0L
        val file = f.str(6).orEmpty().ifEmpty { "<unknown>" }
        val fn = f.str(4).orEmpty()
        val off = f.num(5) ?: 0L
        val build = f.str(8).orEmpty()
        return "#%02d pc %016x  %s".format(n, relPc, file) + (if (fn.isNotEmpty()) " ($fn+$off)" else "") +
            (if (build.isNotEmpty()) " (BuildId: $build)" else "")
    }

    private fun thread(f: List<Field>): Thread =
        Thread(f.num(1) ?: -1L, f.str(2).orEmpty(), f.msgs(4).mapIndexed { n, fr -> frame(n, fr) })

    /**
     * The tombstone [b] as text, or null when it is not one (an older Android's text trace, or anything else). [otherFrames]: how many
     * frames of each other thread; [maxThreads]: how many other threads.
     */
    fun text(b: ByteArray, otherFrames: Int = 4, maxThreads: Int = 40): String? {
        val t = fields(b) ?: return null
        val pid = t.num(5); val tid = t.num(6)
        // a tombstone has at least its pid, tid and its threads (a map: entries of key 1 = tid, value 2 = Thread)
        val threads = t.msgs(16).mapNotNull { e -> e.msgs(2).firstOrNull()?.let { thread(it) } }
        if (pid == null || tid == null || threads.isEmpty()) return null
        val sig = t.msgs(10).firstOrNull()
        val out = StringBuilder("Native crash (the tombstone, decoded)\n")
        t.str(2)?.let { out.append("Build: ").append(it).append('\n') }
        t.str(4)?.let { out.append("Time: ").append(it).append('\n') }
        out.append("pid $pid, tid $tid").append(t.filter { it.no == 9 && it.bytes != null }.joinToString(" ") { it.bytes!!.toString(Charsets.UTF_8) }
            .let { if (it.isEmpty()) "" else ", command $it" }).append('\n')
        if (sig != null) {
            out.append("Signal ${sig.num(1) ?: 0} (${sig.str(2).orEmpty()}), code ${sig.num(3) ?: 0} (${sig.str(4).orEmpty()})")
            if ((sig.num(8) ?: 0L) != 0L) out.append(", fault address 0x%x".format(sig.num(9) ?: 0L))
            out.append('\n')
        }
        t.msgs(15).mapNotNull { it.str(1) }.forEach { out.append("Cause: ").append(it).append('\n') }
        t.str(14)?.takeIf { it.isNotBlank() }?.let { out.append("Abort message: ").append(it).append('\n') }
        val crashing = threads.firstOrNull { it.id == tid }
        if (crashing != null) {
            out.append("\nThe crashing thread: tid ${crashing.id} \"${crashing.name}\"\n")
            crashing.frames.forEach { out.append("  ").append(it).append('\n') }
        }
        val others = threads.filter { it !== crashing }.sortedBy { it.id }
        if (others.isNotEmpty()) out.append("\nThe other threads (their top ${otherFrames} frames)\n")
        for (th in others.take(maxThreads)) {
            out.append("tid ${th.id} \"${th.name}\"\n")
            th.frames.take(otherFrames).forEach { out.append("  ").append(it).append('\n') }
        }
        if (others.size > maxThreads) out.append("[${others.size - maxThreads} more threads]\n")
        return out.toString()
    }
}
