package com.jrees.wowcompanion

import java.io.File
import java.time.LocalDate

/**
 * WHICH WOW: FOREVER CLIENT THE GAME FOLDER HOLDS (launch support, 2026-10-13): the beta (wow_classic_beta, the client until launch day, 4 Nov 2026) and the
 * launch client (wowf, from 4 Nov 2026). Both come from Blizzard's own ProductConfig (us.patch.battle.net:1119/<product>/versions ->
 * tpr/configs/data/<ProductConfig>): `shared_container_default_subfolder` is the flavor folder, the win `relative_path_arm64` the exe.
 * Both use the same CDN path (tpr/wow) and the same 1.60.1 client line.
 *
 * The choice is read from the folder's .build.info, never assumed: the rows of a supported product, Active first (the launcher's
 * activeBuild order). A folder holding BOTH (a launch pre-install next to the beta, one shared Data) prefers the product whose exe is
 * installed; when both are, the beta before [LAUNCH_DAY] and the launch client from that day on ([betaOver]). Pure apart from [of]
 * (reads two files).
 *
 * THE BETA HAS CLOSED (21 Oct 2026, the user: players were left on "no realms"): a folder whose .build.info holds only the beta row stays
 * the beta here; [WowSwitch] adds the launch row and the updater fetches the launch client, after which the installed exe decides.
 */
enum class WowProduct(
    /** .build.info's Product, the patch server's product code, CascLib's code name, .flavor.info, Config.wtf agentUID */
    val code: String,
    /** the flavor folder next to Data (Interface, WTF, Logs, the exe) */
    val flavorDir: String,
    /** the ARM64 client, as Blizzard's ProductConfig names it (looked up without regard to case: [exe]) */
    val exeName: String,
    /** Config.wtf portal, or null for the install's own region (.build.info Branch) */
    val portal: String?,
    /** for the player */
    val title: String,
) {
    LAUNCH("wowf", "_forever_", "Wow-arm64.exe", null, "World of Warcraft: Forever"),
    BETA("wow_classic_beta", "_classic_beta_", "WowB-ARM64.exe", "test", "World of Warcraft: Forever beta");

    /** this product's patch-server URL (versions, cdns) */
    val patchUrl get() = "http://us.patch.battle.net:1119/$code"

    /** the client exe under [root] (the folder with .build.info): the file as it is on disk whatever its case, else the expected name */
    fun exe(root: File): File {
        val dir = File(root, flavorDir)
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(exeName, ignoreCase = true) } ?: File(dir, exeName)
    }

    /** Config.wtf's portal for an install whose row is [row] */
    fun portalFor(row: Map<String, String>?): String =
        portal ?: row?.get("Branch")?.takeIf { it.isNotBlank() }?.lowercase() ?: "us"

    /** the .build.info row the game runs ([row], never null when the file has rows) and its product (null: not a WoW Forever client) */
    data class Choice(val product: WowProduct?, val row: Map<String, String>)

    companion object {
        const val BUILD_INFO = ".build.info"
        /** LAUNCH DAY: the day the client choice moves to World of Warcraft: Forever. Was 4 Nov 2026 ("Beta until launch", the user
         *  2026-10-21); the user 2026-10-27: "The Beta is officially over ... make the change pointed at the Production environment"
         *  (wowf's realms are up for name reservation). The one date the client choice follows */
        val LAUNCH_DAY: LocalDate = LocalDate.of(2026, 10, 27)

        /** the beta is the client no longer on [today]: launch day and after (local date) */
        fun betaOver(today: LocalDate = LocalDate.now()): Boolean = !today.isBefore(LAUNCH_DAY)

        fun forCode(code: String?): WowProduct? = values().firstOrNull { it.code == code }

        /** the one to expect when the folder says nothing (a new folder): the beta while it runs, the launch client once it is over */
        fun expected(today: LocalDate = LocalDate.now()): WowProduct = if (betaOver(today)) LAUNCH else BETA

        /** .build.info text -> its rows, column (without the !TYPE) -> value; a short row is padded with "" */
        fun parse(text: String): List<Map<String, String>> {
            val lines = text.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
            if (lines.size < 2) return emptyList()
            val head = lines[0].split('|').map { it.substringBefore('!') }
            return lines.drop(1).map { line -> line.split('|').let { cols -> head.indices.associate { head[it] to cols.getOrElse(it) { "" } } } }
        }

        /**
         * The row to run, from [rows]: see the class comment. [installed] says whether a product's exe is in the folder. Without a
         * supported row the old order stays (first Active, else first) so the caller can name the wrong game; null without rows.
         */
        fun choose(rows: List<Map<String, String>>, installed: (WowProduct) -> Boolean, today: LocalDate = LocalDate.now()): Choice? {
            if (rows.isEmpty()) return null
            val ours = rows.filter { forCode(it["Product"]) != null }
            if (ours.isEmpty()) return Choice(null, rows.firstOrNull { it["Active"] == "1" } ?: rows.first())
            val pool = ours.filter { it["Active"] == "1" }.ifEmpty { ours }
            var products = pool.mapNotNull { forCode(it["Product"]) }.distinct()
            if (products.size > 1) products = products.filter(installed).ifEmpty { products }
            val product = if (products.size == 1) products[0] else expected(today).takeIf { it in products } ?: products[0]
            return Choice(product, pool.first { it["Product"] == product.code })
        }

        /** [choose] for the game folder [root] (null: no readable .build.info) */
        fun choose(root: File, today: LocalDate = LocalDate.now()): Choice? {
            val text = runCatching { File(root, BUILD_INFO).takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
            return choose(parse(text), { it.exe(root).isFile }, today)
        }

        /** the product of the game folder [root]: its .build.info's choice, else [expected] (a folder without a WoW Forever row too) */
        fun of(root: File, today: LocalDate = LocalDate.now()): WowProduct = choose(root, today)?.product ?: expected(today)

        /** the flavor folder of [root] ([of]) */
        fun flavorDir(root: File): File = File(root, of(root).flavorDir)
    }
}
