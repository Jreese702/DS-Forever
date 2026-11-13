package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * GAME ACCOUNT: ASK EACH TIME (GitHub #47): the game's remembered game-account choice leaves Config.wtf before a start, so the game's own
 * account-select screen comes up. Only key NAMES come back (a value is an account name); absent keys = a no-op. The keys are the PC
 * client's (accountList with its "!" marker, accountName): a device check on a multi-account login still has to confirm them.
 */
class GameAccountAskTest {
    private val secretA = "SECRET_ACCT_ONE"
    private val secretB = "SECRET_ACCT_TWO"
    private val mail = "player@example.invalid"

    private fun wtf(root: File) = File(root, "${WowProduct.LAUNCH.flavorDir}/WTF/Config.wtf")
    private fun seed(lines: List<String>): File {
        val root = Files.createTempDirectory("acct").toFile()
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"))
        wtf(root).appendText(lines.joinToString("\n") + "\n")
        GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"))      // settled: the next ensure changes nothing else
        return root
    }

    @Test fun theMarkedListAndTheNameAreCleared() {
        val (kept, cleared) = GameConfigWtf.forgetAccountChoice(listOf(
            "SET gxApi \"D3D11\"", "SET accountList \"$secretA|!$secretB|\"", "SET accountName \"$mail\"", "SET portal \"us\""))
        assertEquals(listOf("accountList", "accountName"), cleared)
        assertEquals(listOf("SET gxApi \"D3D11\"", "SET portal \"us\""), kept)
    }

    @Test fun nothingRememberedIsANoOp() {
        val noKeys = listOf("SET gxApi \"D3D11\"", "SET portal \"us\"")
        assertEquals(noKeys to emptyList<String>(), GameConfigWtf.forgetAccountChoice(noKeys))
        // a list with no "!" marker and an empty name remember no choice: both stay
        val unmarked = listOf("SET accountList \"$secretA|$secretB|\"", "SET accountName \"\"")
        assertEquals(unmarked to emptyList<String>(), GameConfigWtf.forgetAccountChoice(unmarked))
        assertEquals(emptyList<String>() to emptyList<String>(), GameConfigWtf.forgetAccountChoice(emptyList()))
    }

    @Test fun anyCaseAndSpacingAndNamesComeBackInOneSpelling() {
        val (kept, cleared) = GameConfigWtf.forgetAccountChoice(listOf(
            "  set ACCOUNTLIST \"!$secretA|$secretB|\"", "SET accountname \"$mail\"", "SET accountName \"$mail\"", "# comment"))
        assertEquals(listOf("accountList", "accountName"), cleared)
        assertEquals(listOf("# comment"), kept)
    }

    @Test fun noValueEverComesBack() {
        val (_, cleared) = GameConfigWtf.forgetAccountChoice(listOf("SET accountList \"!$secretA|\"", "SET accountName \"$secretB\""))
        for (n in cleared) {
            assertTrue(n in GameConfigWtf.ACCOUNT_CHOICE_KEYS)
            assertFalse(n.contains(secretA) || n.contains(secretB))
        }
    }

    @Test fun ensureClearsOnlyWhenAsked() {
        val root = seed(listOf("SET accountList \"$secretA|!$secretB|\"", "SET accountName \"$mail\""))
        val before = wtf(root).readText()
        // Remembered (the default): the file as the game left it
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us")))
        assertEquals(before, wtf(root).readText())
        // Ask each time: both lines go, every other line stays
        assertEquals(listOf("accountList", "accountName"),
            GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), askAccount = true))
        val after = wtf(root).readText()
        assertFalse(after.contains(secretA) || after.contains(secretB) || after.contains(mail))
        assertEquals(before.lines().filterNot { it.contains("account", ignoreCase = true) }, after.lines())
        // the next start with nothing remembered: a no-op, the file untouched
        val stamp = wtf(root).lastModified()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), askAccount = true))
        assertEquals(after, wtf(root).readText())
        assertEquals(stamp, wtf(root).lastModified())
    }

    @Test fun aNewFileRemembersNothing() {
        val root = Files.createTempDirectory("acct2").toFile()
        assertEquals(emptyList<String>(), GameConfigWtf.ensure(root, WowProduct.LAUNCH, mapOf("Branch" to "us"), askAccount = true))
        assertTrue(wtf(root).isFile)
    }

    @Test fun theRowIsInGeneralAccount() {
        assertEquals(SettingsTabs.Group.ACCOUNT, SettingsTabs.Block.GAME_ACCOUNT.group)
        assertEquals(SettingsTabs.Tab.GENERAL, SettingsTabs.Block.GAME_ACCOUNT.tab)
        assertEquals(SettingsTabs.Drawer.VIEW, SettingsTabs.Block.GAME_ACCOUNT.drawer)
    }

    @Test fun theRowsWordsAreInSpanishAndFrench() {
        val dir = listOf("../companion/app/src/main/assets/companion/i18n", "companion/app/src/main/assets/companion/i18n")
            .map { File(it) }.first { it.isDirectory }
        for (l in listOf("es", "fr")) {
            val plain = org.json.JSONObject(File(dir, "$l.json").readText()).getJSONObject("plain")
            for (w in listOf("Game account", "Ask each time", "Remembered", "From the next game start"))
                assertTrue("$l: $w", plain.has(w) && plain.getString(w) != w)
        }
    }
}
