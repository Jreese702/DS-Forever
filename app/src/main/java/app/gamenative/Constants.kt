package app.gamenative

import androidx.compose.ui.unit.dp

/**
 * Constants values that may be used around the app more than once.
 * Constants that are used in composables and or view models should be here too.
 */
object Constants {
    object Composables {
        val WINDOW_WIDTH_LARGE = 1200.dp
    }

    object XServer {
        const val DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme,loaddll"
        const val CONTAINER_PATTERN_COMPRESSION_LEVEL = 9
    }
}
