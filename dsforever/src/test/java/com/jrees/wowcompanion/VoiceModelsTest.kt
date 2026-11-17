package com.jrees.wowcompanion

import com.jrees.wowcompanion.VoiceModels.Model
import com.jrees.wowcompanion.VoiceModels.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * OFFLINE VOICE'S MODEL DOWNLOADS (Settings > Add-ons > Voice; the user, 2026-11-16: "optional downloads per language", "part of the
 * addons folder"): the pins, and the download's state machine against a fake host - a 206 appended, a 200 from the start, a 416 or a
 * wrong resume dropping the part, a wrong sha256 deleting it, no room, Cancel keeping the part, Delete removing it all.
 */
class VoiceModelsTest {
    private lateinit var dir: File

    /** a small model for the fake host: its bytes, with the pinned size and sha256 swapped for these (the real ones are 60 / 148 MB) */
    private val body = ByteArray(5_000_000) { (it * 31 + 7).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Before fun setUp() {
        dir = Files.createTempDirectory("voice-models").toFile()
        VoiceModels.freeSpace = { Long.MAX_VALUE }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        VoiceModels.freeSpace = { it.usableSpace }
    }

    // ---------------------------------------------------------------- the pins

    @Test fun pinnedFilesSizesAndHashes() {
        // the quantised pair (the user's pick 2026-11-16 "Offer both"; X-Linked-Size / X-Linked-ETag at the pinned commit, and the
        // whole files' sha256 matched)
        assertEquals("ggml-base.en-q5_1.bin", Model.EN_SMALL.file)
        assertEquals(59_721_011L, Model.EN_SMALL.bytes)
        assertEquals("4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", Model.EN_SMALL.sha256)
        assertEquals("ggml-base-q5_1.bin", Model.MULTI_SMALL.file)
        assertEquals(59_707_625L, Model.MULTI_SMALL.bytes)
        assertEquals("422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", Model.MULTI_SMALL.sha256)
        assertEquals("ggml-base.en.bin", Model.EN.file)
        assertEquals(147_964_211L, Model.EN.bytes)
        assertEquals("a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", Model.EN.sha256)
        assertEquals("ggml-base.bin", Model.MULTI.file)
        assertEquals(147_951_465L, Model.MULTI.bytes)
        assertEquals("60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", Model.MULTI.sha256)
        for (m in Model.values()) {
            assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/${VoiceModels.HF_COMMIT}/${m.file}", m.url)
            assertTrue(m.url.startsWith("https://"))
            assertEquals(if (m.small) "60 MB" else "148 MB", m.size)   // the add-on rows' megabytes (1,000,000 bytes)
            assertEquals(64, m.sha256.length)
        }
        assertEquals(listOf(Model.EN_SMALL, Model.EN, Model.MULTI_SMALL, Model.MULTI), Model.values().toList())
        assertEquals(listOf("Voice: English · 60 MB", "Voice: English · 148 MB", "Voice: other languages · 60 MB",
            "Voice: other languages · 148 MB"), Model.values().map { it.label })
        assertEquals(listOf(false, false, true, true), Model.values().map { it.multi })
        assertEquals(Model.values().map { it.id }.toSet().size, Model.values().size)
        assertEquals(listOf(Model.EN, Model.EN_SMALL), VoiceModels.ofKind(false))
        assertEquals(listOf(Model.MULTI, Model.MULTI_SMALL), VoiceModels.ofKind(true))
        assertEquals("Not downloaded", VoiceModels.line(Model.EN, State.None))
        assertEquals("Downloading 42%", VoiceModels.line(Model.EN, State.Downloading(42)))
        assertEquals("Ready", VoiceModels.line(Model.MULTI, State.Ready))
        assertEquals("Download failed: offline", VoiceModels.line(Model.MULTI, State.Failed("offline")))
    }

    @Test fun modelFilesAreAppFilesNeverTheGamesAddOns() {
        val src = File(repo(), "companion/app/src/main/java/com/jrees/wowcompanion/VoiceModels.kt").readText()
        assertTrue(src.contains("File(c.applicationContext.filesDir, \"dsforever/voice\")"))
        assertFalse(src.contains("Interface/AddOns"))
    }

    // ---------------------------------------------------------------- the fake host

    private class Answer(override val code: Int, val bytes: ByteArray, override val contentRange: String? = null, val cut: Int = -1) : VoiceModels.Conn {
        override val contentLength: Long get() = bytes.size.toLong()
        override val body: InputStream get() = object : InputStream() {
            val inner = ByteArrayInputStream(bytes)
            var n = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (cut in 0..n) throw IOException("unexpected end of stream")
                val want = if (cut >= 0) minOf(len, cut - n) else len
                return inner.read(b, off, want).also { if (it > 0) n += it }
            }
        }
        override fun close() {}
    }

    private val asked = ArrayList<Long?>()

    /** every request answered by [f] (its from), recorded in [asked] */
    private fun host(f: (Long?) -> VoiceModels.Conn): VoiceModels.Net = VoiceModels.Net { _, from -> asked += from; f(from) }

    private fun range(from: Long) = "bytes $from-${body.size - 1}/${body.size}"

    /** EN with its pins swapped for the small test body (reflection on the enum's private fields) */
    private fun withSmallPin(block: () -> Unit) {
        val m = Model.EN
        val fBytes = Model::class.java.getDeclaredField("bytes").apply { isAccessible = true }
        val fSha = Model::class.java.getDeclaredField("sha256").apply { isAccessible = true }
        val bytes0 = fBytes.getLong(m); val sha0 = fSha.get(m)
        fBytes.setLong(m, body.size.toLong()); fSha.set(m, sha)
        try { block() } finally { fBytes.setLong(m, bytes0); fSha.set(m, sha0) }
    }

    private fun part() = File(dir, Model.EN.file + ".part")
    private fun file() = File(dir, Model.EN.file)

    @Test fun aFreshDownloadIsCheckedThenReady() = withSmallPin {
        VoiceModels.start(dir, host { Answer(200, body) }) {}
        assertEquals(State.None, VoiceModels.state(Model.EN))
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertTrue(VoiceModels.ready(Model.EN))
        assertEquals(State.Ready, VoiceModels.state(Model.EN))
        assertFalse(part().exists())
        assertEquals(body.size.toLong(), file().length())
        assertEquals(listOf<Long?>(null), asked)
    }

    @Test fun aCutDownloadResumesWithARange() = withSmallPin {
        var n = 0
        VoiceModels.start(dir, host { from ->
            n++
            if (from == null) Answer(200, body, cut = 2_000_000)
            else Answer(206, body.copyOfRange(from.toInt(), body.size), range(from))
        }) {}
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertEquals(listOf(null, 2_000_000L), asked)
        assertTrue(VoiceModels.ready(Model.EN))
    }

    @Test fun aKeptPartGoesOnAndA200StartsItOver() = withSmallPin {
        part().apply { parentFile.mkdirs(); writeBytes(body.copyOfRange(0, 1_000_000)) }
        // the host ignores the Range: the whole file from 0, the part written over
        VoiceModels.start(dir, host { Answer(200, body) }) {}
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertEquals(listOf<Long?>(1_000_000L), asked)
        assertTrue(VoiceModels.ready(Model.EN))
    }

    @Test fun http416DropsThePart() = withSmallPin {
        part().apply { parentFile.mkdirs(); writeBytes(body.copyOfRange(0, 1_000_000)) }
        VoiceModels.start(dir, host { from -> if (from != null) throw IOException("HTTP 416") else Answer(200, body) }) {}
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertEquals(listOf(1_000_000L, null), asked)
    }

    @Test fun aWrongResumeDropsThePart() = withSmallPin {
        part().apply { parentFile.mkdirs(); writeBytes(body.copyOfRange(0, 1_000_000)) }
        VoiceModels.start(dir, host { from ->
            if (from != null) Answer(206, body.copyOfRange(0, body.size), "bytes 0-${body.size - 1}/${body.size}") else Answer(200, body)
        }) {}
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertEquals(listOf(1_000_000L, null), asked)
    }

    @Test fun aWrongShaDeletesIt() = withSmallPin {
        val bad = body.copyOf().also { it[123] = (it[123] + 1).toByte() }
        VoiceModels.start(dir, host { Answer(200, bad) }) {}
        val s = VoiceModels.fetch(Model.EN)
        assertTrue(s is State.Failed)
        assertEquals("the download's checksum is wrong", (s as State.Failed).why)
        assertFalse(part().exists())
        assertFalse(file().exists())
        assertFalse(VoiceModels.ready(Model.EN))
        assertEquals(s, VoiceModels.state(Model.EN))
    }

    @Test fun aWrongSizeIsRefused() = withSmallPin {
        VoiceModels.start(dir, host { Answer(200, body.copyOf(body.size + 10)) }) {}
        val s = VoiceModels.fetch(Model.EN)
        assertTrue(s.toString(), s is State.Failed)
        assertFalse(file().exists())
    }

    @Test fun noRoomNoDownload() = withSmallPin {
        VoiceModels.freeSpace = { 1_000L }
        VoiceModels.start(dir, host { error("no request without the room") }) {}
        val s = VoiceModels.fetch(Model.EN)
        assertTrue(s is State.NoSpace)
        assertTrue((s as State.NoSpace).line.startsWith("Needs "))
        assertTrue(asked.isEmpty())
    }

    @Test fun anOfflineHostFailsAfterItsRetries() = withSmallPin {
        VoiceModels.start(dir, host { throw java.net.UnknownHostException("huggingface.co") }) {}
        val s = VoiceModels.fetch(Model.EN)
        assertTrue(s.toString(), s is State.Failed)
        assertEquals(CdnRetry.PASSES, asked.size)              // a round that adds nothing is the last one
    }

    @Test fun cancelKeepsThePartDeleteRemovesAll() = withSmallPin {
        var calls = 0
        VoiceModels.start(dir, host { from ->
            calls++
            if (calls == 1) Answer(200, body, cut = 1_500_000)
            else { VoiceModels.cancel(Model.EN); Answer(206, body.copyOfRange(from!!.toInt(), body.size), range(from)) }
        }) {}
        assertEquals(State.None, VoiceModels.fetch(Model.EN))
        assertTrue(part().isFile)
        assertTrue(part().length() in 1_500_000L until body.size.toLong())
        assertFalse(VoiceModels.ready(Model.EN))
        // the next Download goes on from the part
        asked.clear()
        VoiceModels.start(dir, host { from -> Answer(206, body.copyOfRange(from!!.toInt(), body.size), range(from)) }) {}
        assertEquals(State.Ready, VoiceModels.fetch(Model.EN))
        assertEquals(1, asked.size)
        // Delete: the file and its mark go
        VoiceModels.delete(Model.EN)
        assertFalse(file().exists())
        assertFalse(File(dir, Model.EN.file + ".ok").exists())
        assertEquals(State.None, VoiceModels.state(Model.EN))
    }

    @Test fun aFileWithoutItsMarkIsCheckedAgain() = withSmallPin {
        file().apply { parentFile.mkdirs(); writeBytes(body) }
        VoiceModels.start(dir, host { error("no download") }) {}
        assertFalse(VoiceModels.ready(Model.EN))
        assertTrue(VoiceModels.verify(Model.EN))
        assertTrue(VoiceModels.ready(Model.EN))
        // the engine could not load it: its mark goes, so it is checked again
        VoiceModels.distrust(Model.EN)
        assertFalse(VoiceModels.ready(Model.EN))
        // a damaged one is deleted by the check
        file().writeBytes(body.copyOf().also { it[0] = 1; it[1] = 2 })
        assertFalse(VoiceModels.verify(Model.EN))
        assertFalse(file().exists())
    }

    private fun repo(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "companion/tools/whisper_native").isDirectory) d = d.parentFile
        return d ?: error("no repository root")
    }
}
