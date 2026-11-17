package com.jrees.wowcompanion.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A PC typed in by IP (the Thor on another subnet than the PC: mDNS never crosses it). */
class ManualHostTest {
    @Test fun anIpv4IsTakenWithOrWithoutABridgePort() {
        assertEquals(ManualHost.Check.Ok("192.168.2.10"), ManualHost.parse(" 192.168.2.10 "))
        assertEquals(ManualHost.Check.Ok("10.0.0.5", 48211), ManualHost.parse("10.0.0.5:48211"))
        assertEquals(ManualHost.Check.Ok("10.0.0.5"), ManualHost.parse("010.000.000.005"))
    }

    @Test fun anythingElseIsRefusedWithAReason() {
        assertEquals(ManualHost.Check.Empty, ManualHost.parse("  "))
        for (bad in listOf("192.168.1", "192.168.1.256", "a.b.c.d", "192.168.1.1.1", "192.168.1.1:0", "192.168.1.1:99999", "192.168.1.1:x",
            "0.0.0.0", "127.0.0.1", "239.1.1.1", "my-pc.local", "fe80::1")) {
            assertEquals(bad, ManualHost.Check.Invalid, ManualHost.parse(bad))
            assertTrue(ManualHost.message(ManualHost.parse(bad)) != null)
        }
        assertNull(ManualHost.message(ManualHost.parse("192.168.1.20")))
    }

    @Test fun desktopsInfoBecomesThePcMdnsWouldHaveFound() {
        val info = """{"host_id":"d1","pc_name":"DEN-PC","sunshine":{"http_port":52989,"https_port":52984},"pairing_open":true}"""
        val pc = ManualHost.fromInfo("192.168.2.10", 48210, info)!!
        assertEquals(FoundPc(name = "DEN-PC", address = "192.168.2.10", sunshinePort = 52989, bridgePort = 48210, desktopId = "d1", desktopSunshine = 52989), pc)
        assertTrue(pc.hasBridge && pc.hasSunshine)
    }

    @Test fun somethingThatIsNotDesktopIsNotAPc() {
        assertNull(ManualHost.fromInfo("192.168.2.10", 48210, null))
        assertNull(ManualHost.fromInfo("192.168.2.10", 48210, "<html>router</html>"))
        assertNull(ManualHost.fromInfo("192.168.2.10", 48210, """{"hello":"world"}"""))
    }

    @Test fun aDesktopWhoseSunshineIsNotUpIsAPcWithoutAStreamPort() {
        val pc = ManualHost.fromInfo("192.168.2.10", 48211, """{"host_id":"d1","pc_name":"","sunshine":{"http_port":-1}}""")!!
        assertEquals("192.168.2.10", pc.name)
        assertEquals(0, pc.sunshinePort)
    }
}
