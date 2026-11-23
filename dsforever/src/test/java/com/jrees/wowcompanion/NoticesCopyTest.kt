package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's own copies of the notices (app/src/main/assets/notices, what the About page shows) say what the repository's say: the
 * 1523 integration found THIRD_PARTY_NOTICES.txt three 11-20 sections short of the root file (the game-account pick and
 * WORK_OPTIMS pre-write, the upstream host-name / keyboard / game-running service fixes, the HTTPS game updates and Proton without its
 * 32-bit files). The copies keep their own line endings (the notices folder is -text: THIRD_PARTY_NOTICES.txt CRLF,
 * RELEASE_NOTICES.md.txt LF); the text is the same line for line.
 */
class NoticesCopyTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "tools/build_release.py").isFile }

    private fun same(repoFile: String, appCopy: String, crlf: Boolean) {
        val a = File(root, repoFile).readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        val raw = File(root, appCopy).readBytes().toString(Charsets.UTF_8)
        assertEquals("$appCopy is the text of $repoFile", a, raw.replace("\r\n", "\n"))
        if (crlf) assertTrue("$appCopy keeps CRLF", raw.count { it == '\n' } == raw.split("\r\n").size - 1)
        else assertTrue("$appCopy keeps LF", '\r' !in raw)
    }

    @Test fun thirdPartyNoticesCopyIsTheRootFile() =
        same("THIRD_PARTY_NOTICES", "app/src/main/assets/notices/THIRD_PARTY_NOTICES.txt", crlf = true)

    @Test fun releaseNoticesCopyIsTheRootFile() =
        same("RELEASE_NOTICES.md", "app/src/main/assets/notices/RELEASE_NOTICES.md.txt", crlf = false)

    @Test fun theThreeElevenTwentySectionsAreInTheCopy() {
        val t = File(root, "app/src/main/assets/notices/THIRD_PARTY_NOTICES.txt").readText()
        for (h in listOf("## Game-account pick and WORK_OPTIMS pre-write", "## Upstream fixes: host name, keyboard, game-running service",
                "## Upstream changes: HTTPS game updates, Proton without 32-bit files")) assertTrue(h, h in t)
    }
}
