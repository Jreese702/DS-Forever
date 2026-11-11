package com.jrees.wowcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * .build.info repaired before Play (the user, 2026-11-11: updates on slow links): only the running row's EMPTY CDN Hosts / CDN Servers
 * are filled; every other cell, row and the line ending stay; a header without CDN Servers gets the column.
 */
class BuildInfoRepairTest {
    private val head = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Hosts!STRING:0|CDN Servers!STRING:0|Version!STRING:0|Product!STRING:0"
    private val hosts = listOf("level3.blizzard.com", "us.cdn.blizzard.com")
    private val servers = "http://level3.blizzard.com/?maxhosts=4 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"

    @Test fun emptyServersAreFilledAndNothingElseMoves() {
        val text = "$head\r\nus|1|abc|level3.blizzard.com||1.60.1.70888|wowf\r\nus|0|old||||wow_classic_beta\r\n"
        val out = WowSwitch.repairCdn(text, "wowf", hosts, servers)!!
        assertEquals("$head\r\nus|1|abc|level3.blizzard.com|$servers|1.60.1.70888|wowf\r\nus|0|old||||wow_classic_beta\r\n", out)
        val row = WowProduct.parse(out).first { it["Product"] == "wowf" }
        assertEquals("level3.blizzard.com", row["CDN Hosts"])               // its own hosts kept
    }

    @Test fun emptyHostsAndServersBothFilled() {
        val out = WowSwitch.repairCdn("$head\nus|1|abc|||1.60.1.70888|wowf\n", "wowf", hosts, null)!!
        val row = WowProduct.parse(out).single()
        assertEquals("level3.blizzard.com us.cdn.blizzard.com", row["CDN Hosts"])
        assertEquals(WowSwitch.serversFor(hosts), row["CDN Servers"])
        assertEquals("http://level3.blizzard.com/?maxhosts=4 http://us.cdn.blizzard.com/?maxhosts=4 " +
            "https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1", row["CDN Servers"])
        assertEquals("abc", row["Build Key"])
    }

    @Test fun aHealthyFileIsLeftAlone() {
        assertNull(WowSwitch.repairCdn("$head\nus|1|abc|level3.blizzard.com|$servers|1.60.1.70888|wowf\n", "wowf", hosts, servers))
        // another product's (or an inactive) row is not ours to fill
        assertNull(WowSwitch.repairCdn("$head\nus|1|abc|||1.60.1.70888|wow_classic_beta\n", "wowf", hosts, servers))
        assertNull(WowSwitch.repairCdn("$head\nus|0|abc|||1.60.1.70888|wowf\n", "wowf", hosts, servers))
        // nothing to fill it with
        assertNull(WowSwitch.repairCdn("$head\nus|1|abc|||1.60.1.70888|wowf\n", "wowf", emptyList(), null))
    }

    @Test fun anOldHeaderGetsTheServersColumn() {
        val old = "Branch!STRING:0|Active!DEC:1|CDN Hosts!STRING:0|Version!STRING:0|Product!STRING:0\n" +
            "us|1|level3.blizzard.com|1.60.1.70888|wowf\neu|0|eu.cdn.blizzard.com|1.60.1.70245|wow_classic_beta\n"
        val out = WowSwitch.repairCdn(old, "wowf", hosts, servers)!!
        assertEquals("Branch!STRING:0|Active!DEC:1|CDN Hosts!STRING:0|CDN Servers!STRING:0|Version!STRING:0|Product!STRING:0\n" +
            "us|1|level3.blizzard.com|$servers|1.60.1.70888|wowf\neu|0|eu.cdn.blizzard.com||1.60.1.70245|wow_classic_beta\n", out)
    }
}
