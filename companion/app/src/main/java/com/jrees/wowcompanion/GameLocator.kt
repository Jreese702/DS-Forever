package com.jrees.wowcompanion

/**
 * WHERE THE GAME IS, found on the device, never hard-coded (Home, 2026-10-02; the user, for the shared release: "I want this release
 * to be for the wow forever app specifically, but not to use my hard coded paths. ideally the app is able to see that on its own").
 *
 * The WoW Forever app (a GameNative fork) keeps one Wine container per game under its private data:
 *   /data/data/<pkg>/files/imagefs_shared/home/xuser-<id>/.container   (JSON)
 * whose `executablePath` names the game's drive letter and flavor folder (`G:\_classic_beta_\WowB-ARM64.exe`), and whose Wine prefix
 * maps that drive to a real Android folder (`.wine/dosdevices/g: -> /storage/emulated/0/WoW Forever`). A game moved to an SD card is
 * followed, because the link follows it. The `drives` string ("E:/a/bD:/cG:/storage/emulated/0/WoW Forever") is the fallback.
 *
 * Root reads it (the container is the game app's private data; the companion needs root for its taps anyway). [parse*] are pure
 * (home_test); [script] is the root shell's one read, [locate] puts them together.
 */
object GameLocator {

    /** one game, as found */
    data class Found(
        val pkg: String,
        /** the folder holding .build.info and Data (the drive's target) */
        val storage: String,
        /** the flavor folder inside it, e.g. "_classic_beta_" */
        val flavor: String,
        /** the container file (the touch settings live there) */
        val container: String,
    ) {
        val root get() = "$storage/$flavor"
        val log get() = "$root/Logs/General.log"
        val addons get() = "$root/Interface/AddOns"
        val config get() = "$root/WTF/Config.wtf"
    }

    const val WOWFOREVER = "app.wowforever"
    /** the PRIVATE fork of WoW Forever with DS Forever built in (the user 2026-10-08: "Teach DS Forever the fork's app name" (a)): same game folder, its own container */
    const val DSLAUNCHER = "app.dsforever.launcher"

    /** `executablePath` -> (drive letter, flavor folder); null when it does not look like "<L>:\<flavor>\<exe>" */
    fun parseExe(exe: String?): Pair<Char, String>? {
        if (exe == null || exe.length < 4 || exe[1] != ':') return null
        val letter = exe[0].lowercaseChar()
        if (letter !in 'a'..'z') return null
        val parts = exe.substring(2).replace('/', '\\').split('\\').filter { it.isNotEmpty() }
        if (parts.size < 2) return null           // a flavor folder, then the exe
        return letter to parts[0]
    }

    /** the `drives` string -> letter to folder ("E:/data/xD:/storage/emulated/0/DownloadG:/storage/emulated/0/WoW Forever") */
    fun parseDrives(drives: String?): Map<Char, String> {
        if (drives.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<Char, String>()
        val starts = Regex("(?:^|(?<=[^:]))([A-Za-z]):(?=/)").findAll(drives).map { it.range.first }.toList()
        for ((i, s) in starts.withIndex()) {
            val end = if (i + 1 < starts.size) starts[i + 1] else drives.length
            val piece = drives.substring(s, end)
            out[piece[0].lowercaseChar()] = piece.substring(2).trimEnd('/')
        }
        return out
    }

    /** the root shell's one read: every container's path, its JSON on one line, and its drive links */
    fun script(pkg: String) =
        "for c in /data/data/$pkg/files/imagefs_shared/home/*/.container; do [ -f \"\$c\" ] || continue; " +
            "echo \"CONTAINER \$c\"; echo \"JSON \$(tr -d '\\n' < \"\$c\")\"; " +
            "for l in \"\$(dirname \"\$c\")\"/.wine/dosdevices/?:; do [ -L \"\$l\" ] && echo \"LINK \${l##*/} \$(readlink \"\$l\")\"; done; done; echo END"

    /** the script's lines -> the first container that names a game exe whose drive resolves; [exists] checks a folder */
    fun parse(pkg: String, lines: List<String>, exists: (String) -> Boolean): Found? {
        var container: String? = null; var exe: String? = null; var drives: String? = null
        val links = HashMap<Char, String>()
        fun tryOne(): Found? {
            val c = container ?: return null
            val (letter, flavor) = parseExe(exe) ?: return null
            val target = links[letter] ?: parseDrives(drives)[letter] ?: return null
            val storage = target.trimEnd('/')
            return if (exists("$storage/$flavor")) Found(pkg, storage, flavor, c) else null
        }
        for (raw in lines) {
            val l = raw.trimEnd('\r')
            when {
                l.startsWith("CONTAINER ") -> {
                    tryOne()?.let { return it }
                    container = l.removePrefix("CONTAINER ").trim(); exe = null; drives = null; links.clear()
                }
                l.startsWith("JSON ") -> {
                    val j = l.removePrefix("JSON ")
                    exe = jsonString(j, "executablePath"); drives = jsonString(j, "drives")
                }
                l.startsWith("LINK ") -> {
                    val rest = l.removePrefix("LINK ")
                    if (rest.length > 3 && rest[1] == ':') links[rest[0].lowercaseChar()] = rest.substring(3).trim()
                }
                l == "END" -> return tryOne()
            }
        }
        return tryOne()
    }

    /** a top-level string value from flat JSON text, with its escapes undone (\\ and \" and \/) - no org.json, so home_test runs it */
    fun jsonString(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\\/", "/").replace("\u0000", "\\")
    }

    /** runs [script] through root and [parse]s it; null when the game is not installed or set up yet */
    fun locate(pkg: String = WOWFOREVER): Found? = if (!RootPolicy.allowed) null else try {      // the launcher: no root, the default log
        val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", script(pkg)))
        p.outputStream.close()
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        parse(pkg, lines) { path -> java.io.File(path).isDirectory || rootIsDir(path) }
    } catch (_: Throwable) { null }

    private fun rootIsDir(path: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "--mount-master", "-c", "[ -d '${path.replace("'", "'\\''")}' ] && echo Y"))
        p.outputStream.close()
        val y = p.inputStream.bufferedReader().readText().trim() == "Y"
        p.waitFor(); y
    } catch (_: Throwable) { false }
}
