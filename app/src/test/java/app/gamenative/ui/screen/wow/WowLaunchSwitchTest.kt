package app.gamenative.ui.screen.wow

import com.jrees.wowcompanion.WowProduct
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The launch switch end to end, off-device: a local server plays Blizzard's patch server and CDN (versions, cdns, the build and CDN
 * configs, a BLTE install manifest and encoding table, the client files, an index) with made-up content. No game data, no network.
 */
class WowLaunchSwitchTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Install Key!HEX:16|IM Size!DEC:4|CDN Path!STRING:0|" +
        "CDN Hosts!STRING:0|CDN Servers!STRING:0|Tags!STRING:0|Armadillo!STRING:0|Last Activated!STRING:0|Version!STRING:0|" +
        "KeyRing!HEX:16|Product!STRING:0"
    private lateinit var betaRow: String
    private val closed = LocalDate.of(2026, 10, 21)

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val served = mutableMapOf<String, ByteArray>()      // CDN path -> bytes
    private val failing = mutableSetOf<String>()                 // CDN paths that answer 500
    private val requests = mutableListOf<String>()
    private var patchUp = true

    // the launch client's files (made up): the exe and a DLL the beta also has
    private val exe = "the wowf exe".toByteArray()
    private val dll = "a shared dll".toByteArray()
    private val exeEkey = md5("e-exe"); private val dllEkey = md5("e-dll")
    private val install = blte(installManifest(listOf("Wow-arm64.exe" to md5(exe), "shared.dll" to md5(dll))))
    private val installEkey = md5("e-install")
    private val encoding = blteChunked(encodingTable(sortedMapOf(md5(exe) to exeEkey, md5(dll) to dllEkey)))
    private val encodingEkey = md5("e-encoding")
    private val archive = md5("archive")
    private val buildConfig = "2582289d81e380163c7a13472df4cb81"
    private val cdnConfig = "6bff2e685995b214c2ff5d08f3344542"

    @Before fun setUp() {
        root = Files.createTempDirectory("dsf-launch-switch").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requests) { requests += path }
                if (path.startsWith("/patch/")) {
                    if (!patchUp) return MockResponse().setResponseCode(503)
                    val host = "127.0.0.1:${server.port}"
                    return when (path) {
                        "/patch/wowf/versions" -> text("Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|" +
                            "VersionsName!String:0|ProductConfig!HEX:16\n## seqn = 1\nus|$buildConfig|$cdnConfig||70532|1.60.1.70532|2ced73c1\n")
                        "/patch/wowf/cdns" -> text("Name!STRING:0|Path!STRING:0|Hosts!STRING:0|Servers!STRING:0|ConfigPath!STRING:0\n" +
                            "us|tpr/wow|$host|http://$host/?maxhosts=4|tpr/configs/data\n")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                val key = path.removePrefix("/tpr/wow/")
                if (key in failing) return MockResponse().setResponseCode(500)
                val bytes = served[key] ?: return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.split("-")?.map { it.toInt() }
                val body = if (range == null) bytes else bytes.copyOfRange(range[0], minOf(range[1] + 1, bytes.size))
                return MockResponse().setResponseCode(if (range == null) 200 else 206).setBody(Buffer().write(body))
            }
        }
        server.start()
        WowClientDownloader.patchUrlFor = { "http://127.0.0.1:${server.port}/patch/${it.code}" }
        betaRow = "us|1|84bea4233331e390fda8724c6f91ffa5|$cdnConfig|1111|17000|tpr/wow|127.0.0.1:${server.port}||" +
            "Windows arm64 US? enUS speech?:Windows arm64 US? enUS text?||2026-10-20T10:00:00Z|1.60.1.70450||wow_classic_beta"
        File(root, ".build.info").writeText("$head\n$betaRow\n")
        File(root, "_classic_beta_").mkdirs()
        File(root, "_classic_beta_/WowB-ARM64.exe").writeText("the beta exe")
        File(root, "_classic_beta_/shared.dll").writeBytes(dll)
        File(root, "Data/indices").mkdirs()

        serve("config", buildConfig, "# Build Configuration\n\ninstall = ${md5("install")} $installEkey\ninstall-size = 100 ${install.size}\n" +
            "encoding = ${md5("encoding")} $encodingEkey\nbuild-name = WOW-70532patch1.60.1_Forever\n")
        serve("config", cdnConfig, "# CDN Configuration\n\narchives = $archive\narchive-group = ${md5("group")}\n")
        serve("data", installEkey, install)
        serve("data", encodingEkey, encoding)
        serve("data", exeEkey, blte(exe))
        serve("data", dllEkey, blte(dll))
        serve("data", "$archive.index", ByteArray(4096))
        serve("data", "${md5("group")}.index", ByteArray(4096))
    }

    @After fun tearDown() {
        WowClientDownloader.patchUrlFor = { it.patchUrl }
        server.shutdown()
        root.deleteRecursively()
    }

    private fun serve(kind: String, key: String, text: String) = serve(kind, key, text.toByteArray())
    private fun serve(kind: String, key: String, bytes: ByteArray) {
        val name = key.substringBefore('.')
        served["$kind/${name.substring(0, 2)}/${name.substring(2, 4)}/$key"] = bytes
    }
    private fun cdnPath(kind: String, key: String) = "$kind/${key.substring(0, 2)}/${key.substring(2, 4)}/$key"

    private fun text(s: String) = MockResponse().setBody(s)

    private fun switch(today: LocalDate = closed, status: MutableList<String> = mutableListOf()) =
        WowClientDownloader.switchToLaunch(root, { status += it }, today)

    @Test fun betaOnlyFolderMovesToWowfAndKeepsTheBetaRow() {
        val status = mutableListOf<String>()
        val result = switch(status = status)
        assertEquals(WowClientDownloader.Switch.Done("1.60.1.70532"), result)
        val info = File(root, ".build.info").readText()
        assertEquals(betaRow, info.lines()[1])                                    // the beta row untouched
        val rows = WowProduct.parse(info)
        val w = rows.single { it["Product"] == "wowf" }
        assertEquals(buildConfig, w["Build Key"])
        assertEquals(installEkey, w["Install Key"])                               // filled from the build config
        assertEquals("1.60.1.70532", w["Version"])
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, closed))
        assertEquals("the wowf exe", File(root, "_forever_/Wow-arm64.exe").readText())
        assertEquals("a shared dll", File(root, "_forever_/shared.dll").readText())
        assertFalse(cdnPath("data", dllEkey) in requests.map { it.removePrefix("/tpr/wow/") })   // copied from the beta, not downloaded
        assertTrue(File(root, "Data/indices/$archive.index").isFile)
        assertTrue(File(root, "Data/config/25/82/$buildConfig").isFile)
        assertEquals("the beta exe", File(root, "_classic_beta_/WowB-ARM64.exe").readText())
        assertTrue(status.all { it.startsWith("Moving to World of Warcraft: Forever…") })
        assertTrue(status.any { it.contains("Downloading Wow-arm64.exe") })
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") || it.name.endsWith(".download") })
        // done: the next start has nothing to do
        assertEquals(WowClientDownloader.Switch.NotDue, switch())
    }

    @Test fun beforeTheDateNothingHappens() {
        val before = File(root, ".build.info").readText()
        assertEquals(WowClientDownloader.Switch.NotDue, switch(LocalDate.of(2026, 10, 20)))
        assertEquals(before, File(root, ".build.info").readText())
        assertTrue(requests.isEmpty())
    }

    @Test fun noNetworkNoSwitchNoDamage() {
        patchUp = false
        val before = File(root, ".build.info").readBytes()
        val result = switch()
        assertTrue(result is WowClientDownloader.Switch.NoAnswer)
        assertTrue((result as WowClientDownloader.Switch.NoAnswer).why.contains("did not answer"))
        assertTrue(before.contentEquals(File(root, ".build.info").readBytes()))
        assertFalse(File(root, "_forever_").exists())
        assertEquals(WowProduct.BETA, WowProduct.of(root, closed))
        // no server at all (the device offline)
        WowClientDownloader.patchUrlFor = { "http://127.0.0.1:1/patch/${it.code}" }
        assertTrue(switch() is WowClientDownloader.Switch.NoAnswer)
        assertTrue(before.contentEquals(File(root, ".build.info").readBytes()))
    }

    @Test fun interruptedMoveResumesWhereItStopped() {
        failing += cdnPath("data", exeEkey)                                       // the CDN fails on the exe (a kill there is the same)
        try { switch(); fail("the move should have stopped") } catch (e: Exception) { /* said on the screen */ }
        val info = File(root, ".build.info").readText()
        assertEquals(betaRow, info.lines()[1])
        assertTrue(WowProduct.parse(info).any { it["Product"] == "wowf" })        // the row is there...
        assertFalse(File(root, "_forever_/Wow-arm64.exe").exists())               // ...the exe is not: the beta still runs
        assertEquals(WowProduct.BETA, WowProduct.of(root, closed))
        assertEquals("a shared dll", File(root, "_forever_/shared.dll").readText())
        // what a kill mid-write leaves: temp files only
        File(root, "_forever_/Wow-arm64.exe.download").writeText("half an exe")
        File(root, ".build.info.tmp").writeText("half a build info")
        failing.clear()
        requests.clear()
        assertEquals(WowClientDownloader.Switch.Done("1.60.1.70532"), switch())
        assertEquals("the wowf exe", File(root, "_forever_/Wow-arm64.exe").readText())
        assertEquals(WowProduct.LAUNCH, WowProduct.of(root, closed))
        assertEquals(2, WowProduct.parse(File(root, ".build.info").readText()).size)
        assertFalse(File(root, "_forever_/Wow-arm64.exe.download").exists())
        assertFalse(cdnPath("data", dllEkey) in requests.map { it.removePrefix("/tpr/wow/") })
    }

    @Test fun pcCopyWithWowfInstalledIsLeftAlone() {
        File(root, ".build.info").writeText("$head\n$betaRow\n" + betaRow.replace("wow_classic_beta", "wowf") + "\n")
        File(root, "_forever_").mkdirs()
        File(root, "_forever_/Wow-arm64.exe").writeText("installed by Battle.net")
        assertEquals(WowClientDownloader.Switch.NotDue, switch())
        assertEquals("installed by Battle.net", File(root, "_forever_/Wow-arm64.exe").readText())
        assertEquals(WowProduct.LAUNCH, WowClientDownloader.product(root))
    }

    // ---------------------------------------------------------------- made-up CASC files

    private fun md5(s: String) = md5(s.toByteArray())
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private fun unhex(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** one 'N' chunk, no chunk table */
    private fun blte(data: ByteArray) = "BLTE".toByteArray() + ByteArray(4) + byteArrayOf('N'.code.toByte()) + data

    /** a chunk table with one 'N' chunk (the encoding table is read by ranges) */
    private fun blteChunked(data: ByteArray): ByteArray {
        val headerSize = 12 + 24
        val b = ByteBuffer.allocate(headerSize + 1 + data.size)
        b.put("BLTE".toByteArray()).putInt(headerSize).putInt(0x0F000001)
        b.putInt(data.size + 1).putInt(data.size).put(ByteArray(16))
        b.put('N'.code.toByte()).put(data)
        return b.array()
    }

    private fun installManifest(files: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val n = files.size
        val mask = ByteArray((n + 7) / 8).also { m -> for (i in 0 until n) m[i / 8] = (m[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        out.write(byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 1, 16))
        out.write(ByteBuffer.allocate(2).putShort(2).array())
        out.write(ByteBuffer.allocate(4).putInt(n).array())
        for (tag in listOf("Windows", "arm64")) { out.write(tag.toByteArray()); out.write(0); out.write(byteArrayOf(0, 1)); out.write(mask) }
        for ((name, ckey) in files) { out.write(name.toByteArray()); out.write(0); out.write(unhex(ckey)); out.write(ByteBuffer.allocate(4).putInt(10).array()) }
        return out.toByteArray()
    }

    private fun encodingTable(keys: Map<String, String>): ByteArray {
        val page = ByteBuffer.allocate(4096)
        for ((ckey, ekey) in keys) { page.put(1); page.put(ByteArray(5)); page.put(unhex(ckey)); page.put(unhex(ekey)) }
        val header = ByteBuffer.allocate(22)
        header.put('E'.code.toByte()).put('N'.code.toByte()).put(1).put(16).put(16).putShort(4).putShort(4).putInt(1).putInt(0).put(0).putInt(0)
        val index = unhex(keys.keys.first()) + ByteArray(16)
        return header.array() + index + page.array()
    }
}
