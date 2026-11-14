package app.gamenative.utils

import android.content.Context
import com.jrees.wowcompanion.Settings
import com.jrees.wowcompanion.WindowsId
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * DS Forever (the user, 2026-11-11: BLZ51914400 identity fix, issue #95): adapted from dienstag0815's upstream PR
 * jaredgei/wow-forever-android#65, GPL-3.0.
 *
 * DS Forever (the user, 2026-11-14: "build it" - the Windows ID switch for the BLZ51914400 wave, after upstream's same-day A/B
 * "Windows ID: Original (v2.3.3)"): Settings > General > Account > Windows ID picks, at each game start, which identity goes in:
 * [WindowsId.PER_INSTALL] (the default: this install's [MachineIdentity], as since 1.0.25) or [WindowsId.ORIGINAL] (v2.3.3's: the
 * prebuilt prefix's shared values put back, [applyOriginal], and no shim host name). The mode that ran is told to [WindowsId] (the
 * Settings row's restart, the problem report) and to the sign-in log; never an identity value.
 *
 * Writes the [MachineIdentity] into the WoW container's own (upper) Wine prefix.
 *
 * Wine facts this relies on (wine-10.0 sources):
 * - wineboot (create_computer_name_keys) calls gethostname() at every wineboot run and stores the
 *   result via SetComputerNameEx as Tcpip\Parameters\Hostname and ControlSet001\...\ComputerName
 *   (upper case, 15 characters), then copies it into the volatile ActiveComputerName key.
 *   GetComputerNameEx(NetBIOS) reads ActiveComputerName, GetComputerNameEx(DnsHostname) reads
 *   Hostname, and winsock gethostname() calls the libc gethostname() (our shim). The registry
 *   values written here and the shim hostname ([MachineIdentity.ENV_HOSTNAME]) are therefore
 *   the same string and all roads lead to the same name.
 * - The registry file stores the machine hive under ControlSet001 (CurrentControlSet is only a
 *   symbolic link key), so the values are written there.
 * - mountmgr.sys (get_filesystem_serial) reads drive_c/.windows-serial as hexadecimal text for the
 *   volume serial of C:.
 */
object MachineIdentityApply {
    private const val CRYPTOGRAPHY = "Software\\Microsoft\\Cryptography"
    private const val WINDOWS_CV = "Software\\Microsoft\\Windows\\CurrentVersion"
    private const val NT_CV = "Software\\Microsoft\\Windows NT\\CurrentVersion"
    private const val WOW_WINDOWS_CV = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion"
    private const val WOW_NT_CV = "Software\\Wow6432Node\\Microsoft\\Windows NT\\CurrentVersion"
    private const val COMPUTER_NAME_KEY = "System\\ControlSet001\\Control\\ComputerName\\ComputerName"
    private const val TCPIP_KEY = "System\\ControlSet001\\Services\\Tcpip\\Parameters"

    const val SERIAL_FILE = "drive_c/.windows-serial"

    /**
     * The prebuilt prefix's own values (Original), when the container has no readable base prefix (a full, not thin, prefix): read
     * from the bundled Proton 11.0 prefix pack's .wine/system.reg (proton-11.0-90624-arm64ec.wcp, prefixPack.txz), the same in every
     * install - the shared identity v2.3.3 showed. The pack has no drive_c/.windows-serial.
     */
    internal val SHIPPED: Map<Pair<String, String>, String> = mapOf(
        (CRYPTOGRAPHY to "MachineGuid") to "\"87447f37-0be9-4ca4-8034-20645c186c64\"",
        (COMPUTER_NAME_KEY to "ComputerName") to "\"LOCALHOST\"",
        (TCPIP_KEY to "Hostname") to "\"localhost\"",
        (NT_CV to "ProductId") to "\"00330-50000-00000-AAOEM\"",
        (NT_CV to "InstallDate") to "dword:4be5019a",
        (WOW_NT_CV to "ProductId") to "\"00330-50000-00000-AAOEM\"",
        (WOW_NT_CV to "InstallDate") to "dword:4be5019a",
        (WINDOWS_CV to "ProductId") to "\"00330-50000-00000-AAOEM\"",
        (WOW_WINDOWS_CV to "ProductId") to "\"00330-50000-00000-AAOEM\"",
    )

    /** the mode the last [applyToContainer] used in this process (the shim's host name follows it); null = none yet */
    @Volatile private var appliedMode: String? = null

    @JvmStatic
    fun identityFile(context: Context): File = File(context.filesDir, MachineIdentity.FILE_NAME)

    @JvmStatic
    fun identity(context: Context): MachineIdentity = MachineIdentity.loadOrCreate(identityFile(context))

    /** Settings > General > Account > Windows ID now (the default when the settings cannot be read) */
    @JvmStatic
    fun chosenMode(context: Context): String =
        runCatching { Settings.init(context); Settings.windowsId }.getOrDefault(WindowsId.DEFAULT)

    /** the host name for [mode]: Per install = the identity's computer name; Original = null (the shim reports the address, as v2.3.3) */
    internal fun hostnameFor(mode: String, name: () -> String): String? =
        if (WindowsId.sanitize(mode) == WindowsId.ORIGINAL) null else name().takeIf { MachineIdentity.isValidHostname(it) }

    /**
     * Hostname for the shim, only for the WoW container; null otherwise (shim keeps old behaviour). The mode is the one this start's
     * [applyToContainer] applied (the registry and the shim always agree), else the setting.
     */
    @JvmStatic
    fun shimHostname(context: Context, container: Container?): String? {
        if (container == null || container.id != MachineIdentity.WOW_CONTAINER_ID) return null
        return hostnameFor(appliedMode ?: chosenMode(context)) { identity(context).computerName }
    }

    /** Applies the chosen identity to the WoW container only; other containers stay as they are. */
    @JvmStatic
    fun applyToContainer(context: Context, container: Container?) {
        if (container == null || container.id != MachineIdentity.WOW_CONTAINER_ID) return
        val mode = chosenMode(context)
        appliedMode = mode
        val wineDir = File(container.rootDir, ".wine")
        val result = try {
            val changed = if (mode == WindowsId.ORIGINAL) applyOriginal(wineDir, ContainerFiles.lowerDir(container))
                          else applyToPrefix(wineDir, identity(context))
            Timber.i("Windows ID %s applied to prefix, %d value(s) changed", WindowsId.label(mode), changed)
            WindowsId.result(changed)
        } catch (e: Exception) {
            Timber.w("Windows ID %s could not be applied: %s", WindowsId.label(mode), e.javaClass.simpleName)
            WindowsId.result(null, e.javaClass.simpleName)
        }
        tell(mode, result)
    }

    /** the Settings row, the problem report and the sign-in log learn which identity this start used (the mode's name only) */
    private fun tell(mode: String, result: String) {
        val now = System.currentTimeMillis()
        runCatching { WindowsId.markLaunched(mode, result, now) }
        runCatching { Settings.windowsIdRan = Triple(mode, now, !result.startsWith("could not")) }
        runCatching { com.jrees.wowcompanion.NativeLogin.logEvent("Windows ID this game start: ${WindowsId.label(mode)} ($result)") }
    }

    private class Target(val key: String, val name: String, val dword: Boolean = false)

    /** every registry value an identity owns, in the order 1.0.25 wrote them */
    private val TARGETS: List<Target> = buildList {
        add(Target(CRYPTOGRAPHY, "MachineGuid"))
        add(Target(COMPUTER_NAME_KEY, "ComputerName"))
        add(Target(TCPIP_KEY, "Hostname"))
        for (key in listOf(NT_CV, WOW_NT_CV)) {
            add(Target(key, "ProductId"))
            add(Target(key, "InstallDate", dword = true))
        }
        for (key in listOf(WINDOWS_CV, WOW_WINDOWS_CV)) add(Target(key, "ProductId"))
    }

    /** The registry values of the identity, one entry per key the Wine prefix holds. */
    fun registryEdits(identity: MachineIdentity): List<WineRegText.Edit> = TARGETS.map { t ->
        when (t.name) {
            "MachineGuid" -> WineRegText.string(t.key, t.name, identity.machineGuid)
            "ComputerName", "Hostname" -> WineRegText.string(t.key, t.name, identity.computerName)
            "ProductId" -> WineRegText.string(t.key, t.name, identity.productId)
            else -> WineRegText.dword(t.key, t.name, identity.installDate.toInt())
        }
    }

    /**
     * Original's registry values: each one as the base prefix's system.reg has it ([baseSystemReg], its raw text, byte for byte), else
     * the shipped value ([SHIPPED]: no readable base, or a value the base lacks). Same targets as [registryEdits], so every value a
     * per-install start wrote is put back.
     */
    fun originalEdits(baseSystemReg: String?): List<WineRegText.Edit> = TARGETS.mapNotNull { t ->
        val raw = baseSystemReg?.let { WineRegText.getRaw(it, t.key, t.name) }?.trimEnd('\r')?.takeIf { it.isNotEmpty() && !it.endsWith("\\") }
            ?: SHIPPED[t.key to t.name]
        raw?.let { WineRegText.Edit(t.key, t.name, it) }
    }

    /**
     * ORIGINAL (v2.3.3's shared identity): [wineDir]/system.reg's identity values back to the base prefix's ([baseWine], the thin
     * container's lower .wine; null = none) and our C: serial file removed (the base has none: Wine's own C: serial, as before 1.0.25).
     * The per-install identity file is not touched. Returns the number of changed values (registry values plus the serial file).
     */
    @JvmStatic
    fun applyOriginal(wineDir: File, baseWine: File?): Int {
        var changed = 0
        val systemReg = File(wineDir, "system.reg")
        val baseReg = baseWine?.let { File(it, "system.reg") }?.takeIf { it.isFile && it.absoluteFile != systemReg.absoluteFile }
        val baseText = baseReg?.let {
            try { String(Files.readAllBytes(it.toPath()), StandardCharsets.ISO_8859_1) } catch (e: IOException) { null }
        }
        if (systemReg.isFile) changed += WineRegText.applyToFile(systemReg, originalEdits(baseText))
        val serial = File(wineDir, SERIAL_FILE)
        // the base's own file shows through the overlay once ours is gone (the shipped base has none)
        if (serial.isFile && serial.delete()) changed++
        return changed
    }

    /**
     * Writes the identity into [wineDir]/system.reg and the volume serial file. Only values that
     * differ are written; the number of changed values (registry values plus serial file) is
     * returned, 0 on an up to date prefix. Keys the prefix does not have are not created.
     */
    @JvmStatic
    fun applyToPrefix(wineDir: File, identity: MachineIdentity): Int {
        var changed = 0
        val systemReg = File(wineDir, "system.reg")
        if (systemReg.isFile) changed += WineRegText.applyToFile(systemReg, registryEdits(identity))
        if (writeSerial(File(wineDir, SERIAL_FILE), identity.volumeSerial)) changed++
        return changed
    }

    private fun writeSerial(file: File, serial: String): Boolean {
        return try {
            val current = if (file.isFile) String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim() else null
            if (current.equals(serial, ignoreCase = true)) return false
            file.parentFile?.let { Files.createDirectories(it.toPath()) }
            Files.write(file.toPath(), (serial + "\n").toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: IOException) {
            false
        }
    }
}
