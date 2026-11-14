package app.gamenative.utils

import com.jrees.wowcompanion.Settings
import com.jrees.wowcompanion.WindowsId
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * WINDOWS ID AT A GAME START (review of feat/windows-id-switch, the user's 2026-11-14 "build it"): the launcher's own call,
 * [MachineIdentityApply.applyToContainer], end to end on a thin WoW container like the app's (upper .wine + the base prefix as its
 * lower): the saved choice picks what goes into the upper's registry, the shim's host name follows the mode that start APPLIED (not a
 * choice made since), the Settings row / report learn the mode, the per-install identity file is never touched by Original, and
 * another container is left alone. WindowsIdApplyTest covers the file edits themselves; this covers the wiring the launcher runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowsIdContainerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File

    private fun excerpt(): String =
        checkNotNull(javaClass.getResourceAsStream("/machineidentity/system_excerpt.reg")) { "missing test resource" }
            .use { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun forgetApplied() {
        MachineIdentityApply::class.java.getDeclaredField("appliedMode").apply { isAccessible = true }.set(null, null)
    }

    @Before fun up() {
        dir = Files.createTempDirectory("windows-id-container").toFile()
        Settings.init(app)
        Settings.windowsId = WindowsId.PER_INSTALL
        Settings.windowsIdRan = null
        WindowsId.forgetLaunchForTest()
        forgetApplied()
        MachineIdentityApply.identityFile(app).delete()
    }

    @After fun down() {
        Settings.windowsId = WindowsId.PER_INSTALL
        Settings.windowsIdRan = null
        WindowsId.forgetLaunchForTest()
        forgetApplied()
        MachineIdentityApply.identityFile(app).delete()
        dir.deleteRecursively()
    }

    /** a .wine dir holding the excerpt as its system.reg */
    private fun wine(at: File): File = at.also { it.mkdirs(); File(it, "system.reg").writeBytes(excerpt().toByteArray(Charsets.ISO_8859_1)) }

    /** the WoW container as the app keeps it: bionic, a Proton build, thin over [base] (null = a full prefix) */
    private fun wowContainer(base: File?, id: String = MachineIdentity.WOW_CONTAINER_ID): Container {
        val root = File(dir, id)
        wine(File(root, ".wine"))
        return Container(id).apply {
            rootDir = root
            setContainerVariant(Container.BIONIC)
            wineVersion = "proton-11.0-arm64ec"
            setBasePrefix(base?.absolutePath ?: "")
        }
    }

    private fun upperReg(c: Container) = String(File(c.rootDir, ".wine/system.reg").readBytes(), Charsets.ISO_8859_1)
    private fun serial(c: Container) = File(File(c.rootDir, ".wine"), MachineIdentityApply.SERIAL_FILE)

    @Test fun theChoiceDrivesTheStartAndTheShimFollowsWhatWasApplied() {
        val base = wine(File(dir, "base/.wine"))
        val c = wowContainer(base)
        assertEquals("the container is thin over the base", base.absolutePath, ContainerFiles.lowerDir(c)?.absolutePath)

        // Per install (the default): this install's identity in, the shim names it
        MachineIdentityApply.applyToContainer(app, c)
        val perInstall = upperReg(c)
        assertNotEquals(excerpt(), perInstall)
        assertTrue(serial(c).isFile)
        val idFile = MachineIdentityApply.identityFile(app)
        assertTrue(idFile.isFile)
        val idBytes = idFile.readBytes()
        val name = MachineIdentityApply.shimHostname(app, c)
        assertNotNull(name)
        assertTrue(name!!, name.startsWith("DESKTOP-"))
        assertEquals(WindowsId.PER_INSTALL, WindowsId.launched)
        assertEquals(WindowsId.PER_INSTALL, Settings.windowsIdRan?.first)
        assertEquals(true, Settings.windowsIdRan?.third)

        // Original picked while that game runs: nothing changes until the next start (the shim stays with the registry)
        Settings.windowsId = WindowsId.ORIGINAL
        assertEquals("the running start's mode, not the new choice", name, MachineIdentityApply.shimHostname(app, c))
        assertEquals(perInstall, upperReg(c))

        // the next start: the base's shared values back, our serial gone, no host name for the shim, the identity file untouched
        MachineIdentityApply.applyToContainer(app, c)
        assertEquals(excerpt(), upperReg(c))
        assertFalse(serial(c).exists())
        assertNull(MachineIdentityApply.shimHostname(app, c))
        assertEquals(WindowsId.ORIGINAL, WindowsId.launched)
        assertEquals(WindowsId.ORIGINAL, Settings.windowsIdRan?.first)
        assertTrue("Original never rewrites the per-install identity", idBytes.contentEquals(idFile.readBytes()))
        // the base prefix itself is never written
        assertEquals(excerpt(), String(File(base, "system.reg").readBytes(), Charsets.ISO_8859_1))

        // back to Per install: the same identity as before, byte for byte
        Settings.windowsId = WindowsId.PER_INSTALL
        assertNull("still the running (Original) start's", MachineIdentityApply.shimHostname(app, c))
        MachineIdentityApply.applyToContainer(app, c)
        assertEquals(perInstall, upperReg(c))
        assertTrue(serial(c).isFile)
        assertEquals(name, MachineIdentityApply.shimHostname(app, c))
    }

    @Test fun aFullPrefixGetsTheShippedValues() {
        val c = wowContainer(null)
        assertNull(ContainerFiles.lowerDir(c))
        MachineIdentityApply.applyToContainer(app, c)
        assertNotEquals(excerpt(), upperReg(c))
        Settings.windowsId = WindowsId.ORIGINAL
        MachineIdentityApply.applyToContainer(app, c)
        assertEquals(excerpt(), upperReg(c))
        assertFalse(serial(c).exists())
    }

    @Test fun anotherContainerIsLeftAlone() {
        Settings.windowsId = WindowsId.ORIGINAL
        val other = wowContainer(null, id = "some_other_game")
        MachineIdentityApply.applyToContainer(app, other)
        assertEquals(excerpt(), upperReg(other))
        assertNull(MachineIdentityApply.shimHostname(app, other))
        assertNull("no game start told", WindowsId.launched)
        assertNull(Settings.windowsIdRan)
        assertFalse("no identity made for it", MachineIdentityApply.identityFile(app).exists())
        MachineIdentityApply.applyToContainer(app, null)
        assertNull(WindowsId.launched)
    }
}
