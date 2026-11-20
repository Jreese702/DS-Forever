package com.jrees.wowcompanion

import java.io.IOException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blizzard over HTTPS (upstream v2.3.7 7d0b00d0 + v2.3.9 efa6e700): the server list and the certificate skip */
class CdnHttpsTest {
    @After fun tearDown() {
        CdnHttps.forgetUntrusted()
        CdnHttps.clock = System::currentTimeMillis
    }

    /** the cdns table's rows as Blizzard's version service gave them, 2026-11-20 */
    private val us = "http://level3.blizzard.com/?maxhosts=8 http://us.cdn.blizzard.com/?maxhosts=4&fallback=1 " +
        "https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1 https://us.cdn.blizzard.com/?maxhosts=4&fallback=1"
    private val kr = "http://blizzard.gcdn.cloudn.co.kr/?fallback=1 http://kr.cdn.blizzard.com/?maxhosts=2 http://level3.blizzard.com/?maxhosts=4 " +
        "https://blizzard.gcdn.cloudn.co.kr/?maxhosts=4&fallback=1 https://kr.cdn.blizzard.com/?maxhosts=4&fallback=1 " +
        "https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1"

    @Test fun theHttpsServersWithoutTheirOptions() {
        assertEquals(listOf("https://level3.ssl.blizzard.com", "https://us.cdn.blizzard.com"), CdnHttps.servers(us, "level3.blizzard.com us.cdn.blizzard.com"))
        assertEquals(listOf("https://blizzard.gcdn.cloudn.co.kr", "https://kr.cdn.blizzard.com", "https://level3.ssl.blizzard.com"),
            CdnHttps.servers(kr, "level3.blizzard.com"))
    }

    @Test fun elseTheHostsOverHttpsNeverPlainHttp() {
        assertEquals(listOf("https://level3.ssl.blizzard.com", "https://kr.cdn.blizzard.com"),
            CdnHttps.servers("http://level3.blizzard.com/", "level3.blizzard.com kr.cdn.blizzard.com"))
        assertEquals(listOf("https://level3.ssl.blizzard.com", "https://us.cdn.blizzard.com"), CdnHttps.servers(null, null))
        assertEquals(listOf("https://level3.ssl.blizzard.com", "https://us.cdn.blizzard.com"), CdnHttps.servers("", "  "))
        assertTrue(CdnHttps.servers(us, "x").none { it.startsWith("http:") })
        // only the tests' loopback server is plain HTTP
        assertEquals(listOf("http://127.0.0.1:8080"), CdnHttps.servers("http://127.0.0.1:8080/?maxhosts=4", "127.0.0.1:8080"))
        assertEquals(listOf("https://127.0.0.1"), CdnHttps.servers(null, "127.0.0.1"))
    }

    @Test fun onlyACertificateFailureSkipsAServer() {
        val expired = SSLHandshakeException("Chain validation failed").apply { initCause(CertificateExpiredException("expired")) }
        assertTrue(CdnHttps.isCertificateFailure(expired))
        assertTrue(CdnHttps.isCertificateFailure(SSLPeerUnverifiedException("Hostname not verified")))
        assertFalse("a reset handshake on a weak link", CdnHttps.isCertificateFailure(SSLException("Connection reset")))
        assertFalse(CdnHttps.isCertificateFailure(IOException("timeout")))

        assertFalse(CdnHttps.note("https://kr.cdn.blizzard.com", SSLException("Connection reset")))
        assertTrue(CdnHttps.trusted("https://kr.cdn.blizzard.com"))
        assertTrue(CdnHttps.note("https://blizzard.gcdn.cloudn.co.kr", expired))
        assertFalse(CdnHttps.trusted("https://blizzard.gcdn.cloudn.co.kr"))
        assertFalse(CdnHttps.trusted("https://BLIZZARD.gcdn.cloudn.co.kr/tpr/wow"))
        assertEquals(listOf("https://kr.cdn.blizzard.com", "https://level3.ssl.blizzard.com"), CdnHttps.usable(CdnHttps.servers(kr, "")))
    }

    @Test fun theSkipEndsAndNeverEmptiesTheList() {
        var now = 1_000_000L
        CdnHttps.clock = { now }
        val bad = "https://blizzard.gcdn.cloudn.co.kr"
        CdnHttps.note(bad, SSLPeerUnverifiedException("no"))
        assertEquals("every server skipped = all asked again", listOf(bad), CdnHttps.usable(listOf(bad)))
        now += CdnHttps.SKIP_MS - 1
        assertFalse(CdnHttps.trusted(bad))
        now += 1
        assertTrue("trusted again after the skip", CdnHttps.trusted(bad))
    }
}
