package com.jrees.wowcompanion

/**
 * EVERY GAME-FOLDER PATH FROM THE ONE CHOSEN GAME ROOT (Track C, test11 2026-10-12: the user's game is in Documents/WoW Forever, the
 * add-ons installed there, but Home stayed "Waiting for game" - the companion followed the log at the hard-coded old default
 * /storage/emulated/0/WoW Forever). The root is the launcher's own choice (prefs wow_forever / game_path, [GameFiles.gameRoot]); the
 * old constant ([LogTail.WOWFOREVER_PATH]) is only the last fallback. Pure: strings in, strings out.
 */
object GamePaths {
    /** the beta's flavor folder: only for the old default log below; a real root's flavor comes from its .build.info ([WowProduct]) */
    const val FLAVOR = "_classic_beta_"
    /** the old default folder's log: only the last fallback (LogTail.WOWFOREVER_PATH is this) */
    const val OLD_DEFAULT_LOG = "/storage/emulated/0/WoW Forever/$FLAVOR/Logs/General.log"

    /** the game's General.log under a game root (the folder with .build.info and Data), in its client's flavor folder ([WowProduct.of]) */
    fun log(root: String): String = log(root, WowProduct.of(java.io.File(root)))

    /** the game's General.log under [root] for [product] */
    fun log(root: String, product: WowProduct): String = root.trimEnd('/', '\\') + "/${product.flavorDir}/Logs/General.log"

    /**
     * The log to follow. Inside the launcher ([inLauncher]) the chosen root is THE root (no root access, no locator). In the standalone
     * app the located game (its Wine prefix's drive) comes first, then the chosen root; the old default last.
     */
    fun follow(inLauncher: Boolean, chosenRoot: String?, located: String?): String = when {
        inLauncher && !chosenRoot.isNullOrEmpty() -> log(chosenRoot)
        located != null -> located
        !chosenRoot.isNullOrEmpty() -> log(chosenRoot)
        else -> OLD_DEFAULT_LOG
    }
}
