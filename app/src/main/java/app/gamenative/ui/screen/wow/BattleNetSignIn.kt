package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * DS Forever fork: THE LAUNCHER KEEPS NO BATTLE.NET LOGIN (the user, 2026-10-08: "I would really like to remove and avoid using the
 * Txt file for credentials at all costs"). jaredgei's saved login, its dialog and the login.txt it wrote before every launch are gone;
 * sign-in is DS Forever's own. What is left only CLEANS UP: a login.txt left in the game folder and any saved login of an older build.
 */
object BattleNetSignIn {
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun removeLoginFile(gameRoot: File) {
        try {
            val file = File(File(gameRoot, WowClientDownloader.FLAVOR_DIR), "login.txt")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove login.txt")
        }
    }

    private const val PREFS = "battle_net_login"
}
