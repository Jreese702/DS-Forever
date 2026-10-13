package app.gamenative.ui.screen

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object WoWLauncher : PluviaScreen("wow_launcher")
    data object XServer : PluviaScreen("xserver")
}
