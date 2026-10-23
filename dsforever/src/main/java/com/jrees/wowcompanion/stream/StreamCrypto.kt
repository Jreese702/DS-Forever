package com.jrees.wowcompanion.stream

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** hex as GameStream speaks it (upper case out, either case in) */
object Hex {
    private val HEX = "0123456789ABCDEF".toCharArray()
    fun of(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) { val v = b[i].toInt() and 0xFF; out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 15] }
        return String(out)
    }
    fun bytes(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length ${s.length}" }
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[i * 2], 16); val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    /** a certificate's pin: SHA-256 of its DER, lower-case hex (the bridge protocol's "fp") */
    fun fingerprint(der: ByteArray): String = of(sha256(der)).lowercase()
}

/** the few fields read from GameStream's XML answers (<root status_code="200"><tag>value</tag>...</root>) */
object XmlFields {
    /** the first <[tag]>value</[tag]> (entities undone), or null; a self-closed <tag/> is "" */
    fun get(xml: String, tag: String): String? {
        val m = Regex("<$tag(\\s[^>]*)?(/>|>(.*?)</$tag>)", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        if (m.groupValues[2] == "/>") return ""
        return unescape(m.groupValues[3].trim())
    }

    /** the root's status_code (200 = good; 401 = not paired / wrong cert on https); null = not present */
    fun statusCode(xml: String): Int? = Regex("<root[^>]*\\sstatus_code=\"(-?\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()
    fun statusMessage(xml: String): String? = Regex("<root[^>]*\\sstatus_message=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)?.let { unescape(it) }

    /** every <App>...</App> of an applist answer as (ID, AppTitle) */
    fun apps(xml: String): List<Pair<Int, String>> = Regex("<App>(.*?)</App>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val id = get(body, "ID")?.toIntOrNull() ?: return@mapNotNull null
        id to (get(body, "AppTitle") ?: "")
    }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/**
 * MINIMAL DER (X.690) for one thing: the app's self-signed client certificate. moonlight-android builds it with Bouncy Castle; DS
 * Forever has no Bouncy Castle (and adds no download for it), and the certificate is a fixed shape: v3, a random serial,
 * SHA256withRSA, issuer = subject = CN "NVIDIA GameStream Client" (the name every Moonlight client uses), 20 years.
 */
object Der {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }
    fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(body.size) + body
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun nul() = byteArrayOf(0x05, 0x00)
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bits(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    fun explicit(n: Int, body: ByteArray) = tlv(0xA0 + n, body)
    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val chunks = ArrayList<Int>()
            var x = v
            chunks.add((x and 0x7F).toInt()); x = x ushr 7
            while (x > 0) { chunks.add(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            for (c in chunks.asReversed()) out.write(c)
        }
        return tlv(0x06, out.toByteArray())
    }
    /** UTCTime before 2050, GeneralizedTime from then on (RFC 5280 4.1.2.5) */
    fun time(d: Date): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = d }
        val y = c.get(Calendar.YEAR)
        fun two(v: Int) = "%02d".format(v)
        val rest = two(c.get(Calendar.MONTH) + 1) + two(c.get(Calendar.DAY_OF_MONTH)) + two(c.get(Calendar.HOUR_OF_DAY)) +
            two(c.get(Calendar.MINUTE)) + two(c.get(Calendar.SECOND)) + "Z"
        return if (y < 2050) tlv(0x17, (two(y % 100) + rest).toByteArray()) else tlv(0x18, ("%04d".format(y) + rest).toByteArray())
    }

    const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    const val CN = "2.5.4.3"
}

/**
 * THE APP'S MOONLIGHT IDENTITY: one RSA-2048 key and a self-signed certificate, made once and kept (Sunshine remembers a paired
 * client by this certificate). [pem] is what the pairing sends (Unix line endings: the PC side insists, per moonlight-android).
 */
class ClientIdentity(val cert: X509Certificate, val key: PrivateKey) {
    val pem: String get() = toPem(cert.encoded)
    val fingerprint: String get() = Hex.fingerprint(cert.encoded)

    companion object {
        const val COMMON_NAME = "NVIDIA GameStream Client"

        fun generate(rng: SecureRandom = SecureRandom(), now: Date = Date(), commonName: String = COMMON_NAME): ClientIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rng) }
            val kp = kpg.generateKeyPair()
            val serial = BigInteger(64, rng).add(BigInteger.ONE)
            val until = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = now; add(Calendar.YEAR, 20) }.time
            val alg = Der.seq(Der.oid(Der.SHA256_WITH_RSA), Der.nul())
            val name = Der.seq(Der.set(Der.seq(Der.oid(Der.CN), Der.utf8(commonName))))
            val tbs = Der.seq(
                Der.explicit(0, Der.int(BigInteger.valueOf(2))),
                Der.int(serial), alg, name,
                Der.seq(Der.time(now), Der.time(until)),
                name, kp.public.encoded,
            )
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
            val der = Der.seq(tbs, alg, Der.bits(sig))
            return ClientIdentity(parseCert(der), kp.private)
        }

        /** a certificate from DER or PEM bytes */
        fun parseCert(bytes: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate

        fun toPem(der: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(der).chunked(64).joinToString("\n")
            return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
        }

        /** kept as (certificate PEM, key PKCS#8 base64) */
        fun restore(certPem: String, keyPkcs8B64: String): ClientIdentity {
            val cert = parseCert(certPem.toByteArray())
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyPkcs8B64)))
            return ClientIdentity(cert, key)
        }
        fun keyB64(id: ClientIdentity): String = Base64.getEncoder().encodeToString(id.key.encoded)
    }
}

/** AES-128-ECB over whole blocks, as GameStream's pairing uses it (the input zero-padded to a block) */
object PairAes {
    private fun run(mode: Int, data: ByteArray, key: ByteArray): ByteArray {
        val rounded = (data.size + 15) and 15.inv()
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data.copyOf(rounded))
    }
    fun encrypt(data: ByteArray, key: ByteArray) = run(Cipher.ENCRYPT_MODE, data, key)
    fun decrypt(data: ByteArray, key: ByteArray) = run(Cipher.DECRYPT_MODE, data, key)
}
