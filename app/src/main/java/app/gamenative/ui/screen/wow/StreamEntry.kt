package app.gamenative.ui.screen.wow

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import timber.log.Timber

/**
 * STREAMING'S DOOR (S1 "Chooser" -> S2 "Thor streaming"; STREAMING_PLAN.md): what the launcher shows once Streaming is picked on the
 * start-up chooser (or its shortcut opened the app). S1 owns this file and ships [PlaceholderStreamEntry]; S2 plugs in WITHOUT editing
 * S1's files by adding ONE object of its own:
 *
 *     package app.gamenative.stream
 *     object DsStreamEntry : app.gamenative.ui.screen.wow.StreamEntry { ... }
 *
 * [StreamEntries.current] finds it by that name ([StreamEntries.PLUGIN]; app/proguard-rules.pro keeps it through R8) and falls back to
 * the placeholder when it is absent.
 *
 * The bottom screen: while [com.jrees.wowcompanion.ModeBridge.streamCovers] is true the companion shows Streaming's placeholder page
 * (B = back to the chooser) with [com.jrees.wowcompanion.ModeBridge.streamNote] as its status line; set it false once the PC is linked
 * and the companion (Home, the rooms) shows as in Local.
 */
interface StreamEntry {
    /**
     * The TOP screen in Streaming mode (the launcher's own display, where the game's container renders in Local): the pairing UI
     * ("Enter code"), then the stream. [onBack] returns to the start-up chooser (it refuses while [com.jrees.wowcompanion.ModeBridge]
     * says no, e.g. a launch is busy).
     */
    @Composable
    fun TopScreen(onBack: () -> Unit)

    /** Streaming's top screen came up (discovery, reconnect to the last PC ...): main thread */
    fun opened(context: Context) {}

    /** it went (back to the chooser, or the activity is gone): stop what [opened] started */
    fun closed(context: Context) {}

    /**
     * The controller's keys while Streaming's top screen is up (the launcher's activity gets every key: gamepad and D-pad sources,
     * down and up). true = taken (e.g. sent to the PC); false = the bottom page's (A / B / D-pad: B goes back to the chooser).
     */
    fun key(event: KeyEvent): Boolean = false
}

object StreamEntries {
    /** S2's entry, by name (a Kotlin object: its INSTANCE field) */
    const val PLUGIN = "app.gamenative.stream.DsStreamEntry"

    val current: StreamEntry by lazy { load(PLUGIN) }

    fun load(name: String): StreamEntry = try {
        (Class.forName(name).getField("INSTANCE").get(null) as StreamEntry).also { Timber.i("Streaming entry: $name") }
    } catch (_: ClassNotFoundException) {
        PlaceholderStreamEntry
    } catch (t: Throwable) {
        Timber.w(t, "Streaming entry $name unusable: the placeholder")
        PlaceholderStreamEntry
    }
}
