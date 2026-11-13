package com.jrees.wowcompanion.stream

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate

/** the PC's side of Moonlight's pairing, as the handshake needs it (NvHttp on the device, a fake Sunshine in the tests) */
interface PairHttp {
    /** GET http://pc:47989/pair?devicename=..&updateState=1&[args] -> the XML answer. [waitForUser] = no read time-out (the PIN wait) */
    fun pairCommand(args: String, waitForUser: Boolean): String
    /** GET https://pc:<https>/pair?...&phrase=pairchallenge, TLS pinned to [serverCert] -> the XML answer */
    fun pairChallenge(serverCert: X509Certificate): String
    /** GET http://pc:47989/unpair (best effort) */
    fun unpair()
}

/**
 * MOONLIGHT'S PAIRING HANDSHAKE, ported from moonlight-android's PairingManager.java (GPL-3.0; b48494cb), with javax.crypto in place
 * of Bouncy Castle's AES engine. The PIN is DS Forever Desktop's 6-digit code (STREAMING_PLAN.md section 2: the app runs the handshake
 * with THAT code; the bridge hands the same code to the bundled Sunshine), so no Sunshine page is ever shown.
 *
 * The steps (each an HTTP GET answered with <paired>1</paired> or not):
 *  1. getservercert: our salt + certificate; the PC answers once its PIN is in, with ITS certificate (plaincert);
 *  2. clientchallenge: a random challenge, AES-encrypted with sha(salt + PIN);
 *  3. serverchallengeresp: our hash over the PC's challenge, our certificate's signature and a secret;
 *  4. the PC's signed secret is checked (its certificate), and its hash over OUR challenge proves it knew the PIN (else PIN_WRONG);
 *  5. clientpairingsecret: our secret, signed; 6. pairchallenge over TLS pinned to the PC's certificate.
 * Any failure after step 1 unpairs, as upstream does.
 */
class MoonPairing(
    private val http: PairHttp,
    private val identity: ClientIdentity,
    private val rng: SecureRandom = SecureRandom(),
) {
    enum class State { PAIRED, PIN_WRONG, FAILED, ALREADY_IN_PROGRESS }

    data class Result(val state: State, val serverCert: X509Certificate? = null, val why: String = "")

    /** [serverMajorVersion] = the first number of serverinfo's appversion (7+ = SHA-256, older = SHA-1) */
    fun pair(serverMajorVersion: Int, pin: String): Result {
        val hash = if (serverMajorVersion >= 7) "SHA-256" else "SHA-1"
        val hashLen = if (serverMajorVersion >= 7) 32 else 20
        fun h(b: ByteArray) = MessageDigest.getInstance(hash).digest(b)
        fun paired(xml: String) = XmlFields.get(xml, "paired") == "1"

        val salt = random(16)
        val aesKey = h(salt + pin.toByteArray(Charsets.UTF_8)).copyOf(16)

        // 1. no read time-out: the PC answers once its PIN is in (the bridge puts it there)
        val getCert = http.pairCommand("phrase=getservercert&salt=${Hex.of(salt)}&clientcert=${Hex.of(identity.pem.toByteArray())}", true)
        if (!paired(getCert)) return Result(State.FAILED, why = "the PC refused to pair (${XmlFields.statusMessage(getCert) ?: "no reason"})")
        val certHex = XmlFields.get(getCert, "plaincert")
        if (certHex.isNullOrEmpty()) {
            // another device is pairing at the same moment: the PC answers without a certificate
            safeUnpair(); return Result(State.ALREADY_IN_PROGRESS, why = "another device is pairing with this PC")
        }
        val serverCert = try { ClientIdentity.parseCert(Hex.bytes(certHex)) } catch (t: Throwable) {
            safeUnpair(); return Result(State.FAILED, why = "the PC's certificate is unreadable")
        }

        // 2.
        val challenge = random(16)
        val r2 = http.pairCommand("clientchallenge=${Hex.of(PairAes.encrypt(challenge, aesKey))}", false)
        if (!paired(r2)) { safeUnpair(); return Result(State.FAILED, why = "challenge refused") }
        val dec = PairAes.decrypt(Hex.bytes(XmlFields.get(r2, "challengeresponse") ?: return fail("no challenge response")), aesKey)
        if (dec.size < hashLen + 16) return fail("short challenge response")
        val serverResponse = dec.copyOfRange(0, hashLen)
        val serverChallenge = dec.copyOfRange(hashLen, hashLen + 16)

        // 3.
        val clientSecret = random(16)
        val ourHash = h(serverChallenge + identity.cert.signature + clientSecret)
        val r3 = http.pairCommand("serverchallengeresp=${Hex.of(PairAes.encrypt(ourHash, aesKey))}", false)
        if (!paired(r3)) { safeUnpair(); return Result(State.FAILED, why = "challenge answer refused") }

        // 4.
        val secretResp = Hex.bytes(XmlFields.get(r3, "pairingsecret") ?: return fail("no pairing secret"))
        if (secretResp.size <= 16) return fail("short pairing secret")
        val serverSecret = secretResp.copyOfRange(0, 16)
        val serverSig = secretResp.copyOfRange(16, secretResp.size)
        if (!verify(serverSecret, serverSig, serverCert)) { safeUnpair(); return Result(State.FAILED, why = "the PC's signature is wrong (not that PC?)") }
        val expected = h(challenge + serverCert.signature + serverSecret)
        if (!expected.contentEquals(serverResponse)) { safeUnpair(); return Result(State.PIN_WRONG, why = "the code didn't match") }

        // 5.
        val r5 = http.pairCommand("clientpairingsecret=${Hex.of(clientSecret + sign(clientSecret, identity.key))}", false)
        if (!paired(r5)) { safeUnpair(); return Result(State.FAILED, why = "the PC refused our signature") }

        // 6. (seems necessary for the PC to list us as paired, upstream says)
        // issue #219 (1.0.27): the PC answered 401 here on every try after a Desktop update - it took the code (step 5) but its
        // secure port refused the Thor's certificate; said in those words, not as Sunshine's "Certificate verification failed"
        val r6 = try { http.pairChallenge(serverCert) } catch (e: HostHttpException) {
            safeUnpair(); return Result(State.FAILED, why = if (e.code == 401) CERT_REFUSED else "TLS check failed: ${e.message}")
        } catch (t: Throwable) { safeUnpair(); return Result(State.FAILED, why = "TLS check failed: ${t.message}") }
        if (!paired(r6)) { safeUnpair(); return Result(State.FAILED, why = "final check refused") }
        return Result(State.PAIRED, serverCert)
    }

    private fun fail(why: String): Result { safeUnpair(); return Result(State.FAILED, why = why) }
    private fun safeUnpair() { try { http.unpair() } catch (_: Throwable) {} }
    private fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    companion object {
        const val CERT_REFUSED = "the PC took the code but refused the Thor's certificate. Restart DS Forever Desktop, then pair again"
        private fun algo(alg: String) = if (alg == "EC") "SHA256withECDSA" else "SHA256withRSA"
        fun sign(data: ByteArray, key: PrivateKey): ByteArray =
            Signature.getInstance(algo(key.algorithm)).run { initSign(key); update(data); sign() }
        fun verify(data: ByteArray, sig: ByteArray, cert: X509Certificate): Boolean = try {
            Signature.getInstance(algo(cert.publicKey.algorithm)).run { initVerify(cert.publicKey); update(data); verify(sig) }
        } catch (t: Throwable) { false }

        /** serverinfo's appversion "7.1.431.0" -> 7 (0 when unreadable) */
        fun majorVersion(appVersion: String?): Int = appVersion?.substringBefore('.')?.trim()?.toIntOrNull() ?: 0
    }
}
