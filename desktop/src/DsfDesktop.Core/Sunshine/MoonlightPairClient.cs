using System;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace DsfDesktop.Sunshine
{
    /// <summary>
    /// The CLIENT side of Moonlight's PIN pairing (the handshake moonlight-common-c runs on the Thor), used only by Desktop's own
    /// self-test: it proves, on this PC with no device and no web page, that the bundled Sunshine pairs by our PIN. Ported from the
    /// public protocol as Moonlight implements it (getservercert, clientchallenge, serverchallengeresp, clientpairingsecret; SHA-256
    /// for servers reporting appversion 7+). The Thor's real client is S2's JNI build of moonlight-common-c, not this.
    /// </summary>
    public sealed class MoonlightPairClient
    {
        readonly string host;
        readonly int httpPort;
        readonly string uniqueId = "0123456789ABCDEF";
        readonly string uuid = Guid.NewGuid().ToString("N");
        readonly X509Certificate2 cert;
        readonly RSA key;
        public string DeviceName = "dsf-selftest";

        public MoonlightPairClient(string host, int httpPort)
        {
            this.host = host;
            this.httpPort = httpPort;
            key = new RSACng(2048);
            var req = new CertificateRequest("CN=NVIDIA GameStream Client", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        }

        public static string Pem(X509Certificate2 c)
        {
            var b64 = Convert.ToBase64String(c.RawData);
            var sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
            for (int i = 0; i < b64.Length; i += 64) sb.Append(b64.Substring(i, Math.Min(64, b64.Length - i))).Append('\n');
            return sb.Append("-----END CERTIFICATE-----\n").ToString();
        }

        /// <summary>the signatureValue BIT STRING of a DER certificate (what Moonlight calls the cert's signature)</summary>
        public static byte[] CertSignature(byte[] der)
        {
            int p = 0;
            ReadTl(der, ref p, out int outerLen);           // Certificate SEQUENCE
            int end = p + outerLen;
            ReadTl(der, ref p, out int tbsLen); p += tbsLen; // tbsCertificate
            ReadTl(der, ref p, out int algLen); p += algLen; // signatureAlgorithm
            int tag = ReadTl(der, ref p, out int sigLen);    // signatureValue
            if (tag != 0x03 || p + sigLen > end) throw new FormatException("no signature");
            return der.Skip(p + 1).Take(sigLen - 1).ToArray(); // first byte: unused bits (0)
        }

        static int ReadTl(byte[] d, ref int p, out int len)
        {
            int tag = d[p++];
            int l = d[p++];
            if ((l & 0x80) != 0)
            {
                int n = l & 0x7f;
                l = 0;
                for (int i = 0; i < n; i++) l = (l << 8) | d[p++];
            }
            len = l;
            return tag;
        }

        static string Tag(string xml, string name)
        {
            var m = Regex.Match(xml, "<" + name + ">([^<]*)</" + name + ">");
            return m.Success ? m.Groups[1].Value : null;
        }

        async Task<string> Call(HttpClient http, string args, CancellationToken ct)
        {
            var url = "http://" + host + ":" + httpPort + "/pair?uniqueid=" + uniqueId + "&uuid=" + uuid + "&" + args;
            using (var r = await http.GetAsync(url, ct).ConfigureAwait(false))
                return await r.Content.ReadAsStringAsync().ConfigureAwait(false);
        }

        /// <summary>runs the whole handshake with the PIN; null = paired, else why not</summary>
        public async Task<string> Pair(string pin, CancellationToken ct)
        {
            using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(120) })
            using (var sha = SHA256.Create())
            {
                var salt = Crypto.RandomBytes(16);
                var aesKey = sha.ComputeHash(salt.Concat(Encoding.UTF8.GetBytes(pin)).ToArray()).Take(16).ToArray();
                var pem = Encoding.UTF8.GetBytes(Pem(cert));

                var x = await Call(http, "devicename=" + DeviceName + "&updateState=1&phrase=getservercert&salt=" + Crypto.Hex(salt) + "&clientcert=" + Crypto.Hex(pem), ct).ConfigureAwait(false);
                if (Tag(x, "paired") != "1") return "getservercert refused: " + x;
                var serverPem = Encoding.UTF8.GetString(Crypto.FromHex(Tag(x, "plaincert") ?? ""));
                var serverDer = Convert.FromBase64String(string.Concat(serverPem.Split('\n').Where(l => !l.StartsWith("-----")).Select(l => l.Trim())));
                var serverCert = new X509Certificate2(serverDer);

                var challenge = Crypto.RandomBytes(16);
                x = await Call(http, "devicename=" + DeviceName + "&updateState=1&clientchallenge=" + Crypto.Hex(Aes(aesKey, challenge, true)), ct).ConfigureAwait(false);
                var resp = Tag(x, "challengeresponse");
                if (Tag(x, "paired") != "1" || resp == null) return "clientchallenge refused: " + x;
                var dec = Aes(aesKey, Crypto.FromHex(resp), false);
                var serverResponse = dec.Take(32).ToArray();
                var serverChallenge = dec.Skip(32).Take(16).ToArray();

                var clientSecret = Crypto.RandomBytes(16);
                var respHash = sha.ComputeHash(serverChallenge.Concat(CertSignature(cert.RawData)).Concat(clientSecret).ToArray());
                x = await Call(http, "devicename=" + DeviceName + "&updateState=1&serverchallengeresp=" + Crypto.Hex(Aes(aesKey, respHash, true)), ct).ConfigureAwait(false);
                var secretHex = Tag(x, "pairingsecret");
                if (Tag(x, "paired") != "1" || secretHex == null) return "serverchallengeresp refused: " + x;
                var ps = Crypto.FromHex(secretHex);
                var serverSecret = ps.Take(16).ToArray();
                var serverSig = ps.Skip(16).ToArray();
                if (!serverCert.GetRSAPublicKey().VerifyData(serverSecret, serverSig, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1))
                    return "the server's pairing secret is not signed by its certificate (MITM?)";
                var expected = sha.ComputeHash(challenge.Concat(CertSignature(serverDer)).Concat(serverSecret).ToArray());
                if (!expected.SequenceEqual(serverResponse)) return "wrong PIN (the server's challenge response does not match)";

                var sig = key.SignData(clientSecret, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                x = await Call(http, "devicename=" + DeviceName + "&updateState=1&clientpairingsecret=" + Crypto.Hex(clientSecret.Concat(sig).ToArray()), ct).ConfigureAwait(false);
                if (Tag(x, "paired") != "1") return "clientpairingsecret refused: " + x;
                return null;
            }
        }

        static byte[] Aes(byte[] key, byte[] data, bool encrypt)
        {
            using (var a = System.Security.Cryptography.Aes.Create())
            {
                a.Key = key;
                a.Mode = CipherMode.ECB;
                a.Padding = PaddingMode.None;
                using (var t = encrypt ? a.CreateEncryptor() : a.CreateDecryptor()) return t.TransformFinalBlock(data, 0, data.Length);
            }
        }
    }
}
