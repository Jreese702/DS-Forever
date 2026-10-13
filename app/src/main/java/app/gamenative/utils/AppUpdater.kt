package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import com.jrees.wowcompanion.AppUpdate
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * DS FOREVER'S SELF-UPDATE, the Android half (Track C, the user's rulings 2026-10-12: "Download + Android install", "The public release
 * repo", "At startup, never blocks"). The checks and the download are [AppUpdate]'s (pure, host-tested); here:
 *  - the release repo is ONE build constant (BuildConfig.DSF_RELEASE_REPO; empty = none yet: nothing is fetched);
 *  - BEFORE the installer: the downloaded APK must be OUR package, signed by exactly OUR certificate(s), with a higher versionCode
 *    (PackageManager.getPackageArchiveInfo + GET_SIGNING_CERTIFICATES against our own install); anything else is refused and deleted;
 *  - "Install unknown apps": the PLAYER allows it on Android's own screen for this app (ACTION_MANAGE_UNKNOWN_APP_SOURCES); nothing is
 *    ever granted for them; REQUEST_INSTALL_PACKAGES is the only permission it needs (already declared);
 *  - never while the game runs; the cached APK goes once handed over (or on a failure).
 * Every step is logged (WCSETUP, through [log]).
 */
object AppUpdater {
    private const val TAG = "WCSETUP"
    val repo: String get() = BuildConfig.DSF_RELEASE_REPO
    val userAgent: String get() = "DS-Forever/${BuildConfig.VERSION_NAME}"

    fun log(s: String) { Timber.tag(TAG).i(s); android.util.Log.i(TAG, s) }

    private fun dir(context: Context) = File(context.cacheDir, "updates")

    /** the startup check (IO); never throws, never blocks anything */
    suspend fun check(): AppUpdate.Check = withContext(Dispatchers.IO) {
        AppUpdate.check(repo, BuildConfig.VERSION_NAME, userAgent, ::log)
    }

    /** the top screen's line */
    fun line(c: AppUpdate.Check?): String = AppUpdate.line(BuildConfig.VERSION_NAME, c)

    /** download, then verify it is ours and newer; the APK ready for [install], or an exception with the reason (the file deleted) */
    suspend fun download(context: Context, release: AppUpdate.Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val apk = try {
            AppUpdate.download(release, dir(context), userAgent, log = ::log, onProgress = onProgress)
        } catch (t: Throwable) { AppUpdate.clear(dir(context)); throw t }
        val why = verify(context, apk)
        if (why != null) {
            log("update: REFUSED ${apk.name}: $why")
            AppUpdate.clear(dir(context))
            throw SecurityException(why)
        }
        log("update: ${apk.name} verified (our package, our certificate, a newer versionCode)")
        apk
    }

    /** null = ours: the same package, signed by exactly our certificate set, a higher versionCode; else why not */
    fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val them = runCatching { pm.getPackageArchiveInfo(apk.path, flags) }.getOrNull() ?: return "not a readable app"
        val us = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return "our own install could not be read"
        if (them.packageName != context.packageName) return "another app (${them.packageName})"
        val theirs = certs(them); val ours = certs(us)
        if (theirs.isEmpty() || ours.isEmpty()) return "no signing certificate"
        if (theirs != ours) return "a different signing certificate"
        if (code(them) <= code(us)) return "not newer than the installed version"
        return null
    }

    private fun certs(p: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
        } else @Suppress("DEPRECATION") p.signatures
        return raw.orEmpty().mapTo(mutableSetOf()) { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrElse {
        Timber.w(it, "canRequestPackageInstalls check failed")
        false
    }

    /** Android's own "Install unknown apps" screen for THIS app: the player flips it there */
    fun openInstallPermissionSettings(context: Context) {
        log("update: asking the player to allow installs for DS Forever (Android's settings screen)")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        }
    }

    /** hands the verified APK to Android's installer; never while the game runs. False = not handed over (the reason is logged) */
    fun install(context: Context, apkFile: File): Boolean {
        if (com.jrees.wowcompanion.LauncherHooks.gameUp()) { log("update: not while the game runs"); return false }
        if (!canInstall(context)) { openInstallPermissionSettings(context); return false }
        if (verify(context, apkFile) != null) { log("update: the file changed since it was checked; refused"); AppUpdate.clear(dir(context)); return false }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        log("update: ${apkFile.name} handed to Android's installer")
        context.startActivity(intent)
        return true
    }

    /** at startup, ONCE per process: an update file left from an earlier run (installed, or abandoned) goes (a later screen entry keeps
     *  a file this run downloaded, e.g. one waiting for the player's "Install unknown apps") */
    @Volatile private var cleared = false
    fun clearCacheOnce(context: Context) { if (!cleared) { cleared = true; AppUpdate.clear(dir(context)) } }
}
