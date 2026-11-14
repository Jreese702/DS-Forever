package app.gamenative.utils

import com.jrees.wowcompanion.WindowsId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * WINDOWS ID: ORIGINAL (the user, 2026-11-14: "build it"; the BLZ51914400 wave): the switch back to v2.3.3's shared identity puts every
 * value a per-install start wrote back to the base prefix's own, byte for byte, removes our C: serial file, and never touches (or makes)
 * the per-install identity file, so Per install again brings the same identity back. The excerpt is the bundled prefix pack's own
 * system.reg (proton-11.0-90624-arm64ec.wcp, prefixPack.txz, .wine/system.reg: its MachineGuid, ProductId x4, InstallDate x2,
 * ComputerName and Hostname lines are the excerpt's).
 */
class WindowsIdApplyTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("windows-id-test").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun excerpt(): String =
        checkNotNull(javaClass.getResourceAsStream("/machineidentity/system_excerpt.reg")) { "missing test resource" }
            .use { String(it.readBytes(), Charsets.ISO_8859_1) }

    /** a Wine prefix dir with the excerpt as its system.reg */
    private fun prefix(name: String, text: String = excerpt()): File {
        val wine = File(dir, name)
        wine.mkdirs()
        File(wine, "system.reg").writeBytes(text.toByteArray(Charsets.ISO_8859_1))
        return wine
    }

    private fun reg(wine: File) = String(File(wine, "system.reg").readBytes(), Charsets.ISO_8859_1)
    private fun serial(wine: File) = File(wine, MachineIdentityApply.SERIAL_FILE)
    private fun id(seed: Int) = MachineIdentity.generate(Random(seed), 1_800_000_000L)

    @Test fun originalPutsThePerInstallPrefixBackByteForByte() {
        val base = prefix("base")
        val upper = prefix("upper")
        assertEquals(10, MachineIdentityApply.applyToPrefix(upper, id(5)))   // 9 values + the serial file
        assertNotEquals(excerpt(), reg(upper))
        assertTrue(serial(upper).isFile)
        assertEquals(10, MachineIdentityApply.applyOriginal(upper, base))
        assertEquals(excerpt(), reg(upper))
        assertFalse("our C: serial file is gone", serial(upper).exists())
        // a second Original start changes nothing
        assertEquals(0, MachineIdentityApply.applyOriginal(upper, base))
        assertEquals(excerpt(), reg(upper))
    }

    @Test fun noBasePrefixUsesTheShippedValues() {
        val upper = prefix("upper")
        MachineIdentityApply.applyToPrefix(upper, id(9))
        assertEquals(10, MachineIdentityApply.applyOriginal(upper, null))
        assertEquals(excerpt(), reg(upper))
        // a base folder with no system.reg: the same
        MachineIdentityApply.applyToPrefix(upper, id(9))
        assertEquals(10, MachineIdentityApply.applyOriginal(upper, File(dir, "empty").also { it.mkdirs() }))
        assertEquals(excerpt(), reg(upper))
    }

    /** the shipped fallback IS the prefix pack's: every target's raw value equals the excerpt's */
    @Test fun theShippedValuesAreThePrefixPacks() {
        val fromBase = MachineIdentityApply.originalEdits(excerpt())
        val shipped = MachineIdentityApply.originalEdits(null)
        assertEquals(9, fromBase.size)
        assertEquals(fromBase.map { Triple(it.key, it.name, it.raw) }, shipped.map { Triple(it.key, it.name, it.raw) })
        // the same targets 1.0.25's identity writes, in its order
        assertEquals(MachineIdentityApply.registryEdits(id(1)).map { it.key to it.name }, fromBase.map { it.key to it.name })
        for (e in shipped) assertEquals(e.name, WineRegText.getRaw(excerpt(), e.key, e.name), e.raw)
    }

    /** a base that lacks a value: that one from the shipped set (a shared value, never this install's) */
    @Test fun aValueTheBaseLacksComesFromTheShippedSet() {
        val noGuid = excerpt().replace("\"MachineGuid\"=\"87447f37-0be9-4ca4-8034-20645c186c64\"\n", "")
        assertTrue(noGuid != excerpt())
        val e = MachineIdentityApply.originalEdits(noGuid).single { it.name == "MachineGuid" }
        assertEquals("\"87447f37-0be9-4ca4-8034-20645c186c64\"", e.raw)
        // and a base whose own value differs from the shipped one wins (a newer prefix pack)
        val other = excerpt().replace("00330-50000-00000-AAOEM", "00330-50000-00000-AAOEX")
        assertTrue(MachineIdentityApply.originalEdits(other).filter { it.name == "ProductId" }.all { it.raw == "\"00330-50000-00000-AAOEX\"" })
    }

    @Test fun originalNeverTouchesOrMakesTheIdentityFile() {
        val file = File(dir, MachineIdentity.FILE_NAME)
        val upper = prefix("upper")
        // a fresh install that picks Original first: no identity is made
        MachineIdentityApply.applyOriginal(upper, prefix("base"))
        assertFalse(file.exists())
        // an install that ran Per install: its identity stays, and Per install again writes the same one
        val mine = MachineIdentity.loadOrCreate(file, Random(3))
        val bytes = file.readBytes()
        MachineIdentityApply.applyToPrefix(upper, mine)
        val perInstall = reg(upper)
        MachineIdentityApply.applyOriginal(upper, prefix("base2"))
        assertTrue(bytes.contentEquals(file.readBytes()))
        MachineIdentityApply.applyToPrefix(upper, MachineIdentity.loadOrCreate(file, Random(77)))
        assertEquals(perInstall, reg(upper))
    }

    @Test fun theUpperAsItsOwnBaseIsIgnored() {
        val upper = prefix("upper")
        MachineIdentityApply.applyToPrefix(upper, id(4))
        // a base path pointing at the upper itself (a broken container record) must not "restore" the per-install values
        MachineIdentityApply.applyOriginal(upper, upper)
        assertEquals(excerpt(), reg(upper))
    }

    @Test fun noRegistryFileOnlyRemovesTheSerial() {
        val upper = File(dir, "bare").also { it.mkdirs() }
        serial(upper).parentFile!!.mkdirs()
        serial(upper).writeText("1234ABCD\n")
        assertEquals(1, MachineIdentityApply.applyOriginal(upper, null))
        assertFalse(serial(upper).exists())
        assertFalse(File(upper, "system.reg").exists())
        assertEquals(0, MachineIdentityApply.applyOriginal(upper, null))
    }

    @Test fun theShimHostNameFollowsTheMode() {
        assertNull(MachineIdentityApply.hostnameFor(WindowsId.ORIGINAL) { error("Original never reads the identity") })
        assertEquals("DESKTOP-ABC1234", MachineIdentityApply.hostnameFor(WindowsId.PER_INSTALL) { "DESKTOP-ABC1234" })
        assertEquals("DESKTOP-ABC1234", MachineIdentityApply.hostnameFor("anything else") { "DESKTOP-ABC1234" })
        assertNull(MachineIdentityApply.hostnameFor(WindowsId.PER_INSTALL) { "not a host name!" })
    }

    @Test fun keysMissingInThePrefixAreNotCreatedByOriginal() {
        val noCrypto = excerpt().replace(Regex("""\[Software\\\\Microsoft\\\\Cryptography][^\[]*"""), "")
        assertTrue(noCrypto != excerpt())
        val upper = prefix("upper", noCrypto)
        MachineIdentityApply.applyOriginal(upper, null)
        assertFalse(reg(upper).contains("Cryptography"))
        assertEquals(noCrypto, reg(upper))
    }
}
