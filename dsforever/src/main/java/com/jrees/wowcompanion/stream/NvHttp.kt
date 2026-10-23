package com.jrees.wowcompanion.stream

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** an HTTP answer that was not 200, or a GameStream status_code that was not */
class HostHttpException(val code: Int, message: String) : IOException(message)

/**
 * GameStream's HTTP API on the PC (Sunshine's 47989 http / 47984 https), as moonlight-android's NvHTTP.java uses it: serverinfo,
 * pair, applist, launch / resume, cancel. https presents the app's client certificate ([ClientIdentity]) and accepts ONLY the PC's
 * certificate from the pairing (pinned; no CA, no host name check: the pin is the identity).
 */
class NvHttp(
    val address: String,
    val httpPort: Int = 47989,
    var httpsPort: Int = 47984,
    private val identity: ClientIdentity,
    @Volatile var serverCert: X509Certificate? = null,
    private val deviceName: String = "DSForever",
) : PairHttp {

    private fun base(https: Boolean) = if (https) "https://$address:$httpsPort" else "http://$address:$httpPort"

    /** one GET; [readMs] = 0 waits as long as it takes (the PIN wait, launch) */
    fun get(https: Boolean, path: String, query: String? = null, readMs: Int = READ_MS): String {
        val q = buildString {
            append("uniqueid=").append(UNIQUE_ID).append("&uuid=").append(UUID.randomUUID())
            if (!query.isNullOrEmpty()) append('&').append(query)
        }
        val c = URL("${base(https)}/$path?$q").openConnection() as HttpURLConnection
        if (https && c is HttpsURLConnection) {
            c.sslSocketFactory = sslContext().socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }      // the pinned certificate is the check
        }
        c.connectTimeout = CONNECT_MS
        c.readTimeout = readMs
        c.useCaches = false
        try {
            val code = c.responseCode
            if (code != 200) throw HostHttpException(code, "$path: HTTP $code")
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val sc = XmlFields.statusCode(body)
            if (sc != null && sc != 200) throw HostHttpException(sc, "$path: ${XmlFields.statusMessage(body) ?: "status $sc"}")
            return body
        } finally { c.disconnect() }
    }

    fun serverInfo(https: Boolean): String = get(https, "serverinfo", readMs = READ_MS)
    fun appList(): String = get(true, "applist")
    fun cancel(): String = get(true, "cancel", readMs = 0)

    /** launch or resume ([verb]) with the stream's parameters; the answer holds sessionUrl0 */
    fun launch(verb: String, query: String): String = get(true, verb, query, readMs = 0)

    override fun pairCommand(args: String, waitForUser: Boolean): String =
        get(false, "pair", "devicename=$deviceName&updateState=1&$args", readMs = if (waitForUser) 0 else READ_MS)

    override fun pairChallenge(serverCert: X509Certificate): String {
        this.serverCert = serverCert
        return get(true, "pair", "devicename=$deviceName&updateState=1&phrase=pairchallenge")
    }

    override fun unpair() { get(false, "unpair") }

    private fun sslContext(): SSLContext {
        val pinned = serverCert
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("server only")
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                if (pinned == null || !chain[0].encoded.contentEquals(pinned.encoded)) throw CertificateException("not the paired PC's certificate")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val keys = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.cert)
            override fun getPrivateKey(alias: String?): PrivateKey = identity.key
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
    }

    companion object {
        /** every Moonlight client uses this one id (so a session started by one can be resumed / quit by another) */
        const val UNIQUE_ID = "0123456789ABCDEF"
        const val CONNECT_MS = 3_000
        const val READ_MS = 7_000
        private const val ALIAS = "dsf"
    }
}
