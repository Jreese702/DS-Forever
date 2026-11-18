package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #457 (1.0.34 crash report): NullPointerException "InputDevice.getId() on a null object reference" in XServerScreen's key / motion
 * handlers. InputEvent.getDevice() looks the device up again on every call (InputDevice.getDevice(deviceId)), so a pad that went
 * away mid-event (unplugged, a reconnect, the Thor's docked re-enumeration) passes isGameController() and then reads null. The
 * handlers use the event's own int deviceId; no live code may read a property off event.device without ?. again.
 */
class XServerScreenDeviceIdTest {
    @Test fun noUnguardedDeviceReadsInTheInputHandlers() {
        val src = File("src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt")
        val unsafe = Regex("""event\??\.device\.[A-Za-z]""")
        val hits = src.readLines().withIndex()
            .filter { (_, line) -> !line.trimStart().startsWith("//") && unsafe.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        assertEquals("event.device is re-looked-up and can be null: use event.deviceId or ?.", emptyList<String>(), hits)
    }
}
