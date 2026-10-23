using System;
using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace DsfDesktop.Bridge
{
    /// <summary>
    /// The bridge's TLS certificate: self-signed RSA-2048, made once and kept (DataDir\bridge.pfx, its password DPAPI-protected next
    /// to it). The Thor pins its SHA-256 at pairing; replacing it means every device pairs again, so it is never rotated silently.
    /// </summary>
    public static class BridgeCert
    {
        public static X509Certificate2 LoadOrCreate(string dataDir, string hostId)
        {
            var pfx = Path.Combine(dataDir, "bridge.pfx");
            var key = Path.Combine(dataDir, "bridge.pfx.key");
            if (File.Exists(pfx) && File.Exists(key))
            {
                var pass = Crypto.Unprotect(File.ReadAllText(key).Trim());
                if (pass != null)
                {
                    try { return Load(File.ReadAllBytes(pfx), pass); }
                    catch (Exception e) { Log.I("bridge certificate unreadable, making a new one (devices must pair again): " + e.Message); }
                }
            }
            var newPass = Crypto.Base64Url(Crypto.RandomBytes(24));
            byte[] bytes;
            using (var rsa = new RSACng(2048))
            {
                var req = new CertificateRequest("CN=DS Forever Desktop " + (hostId ?? ""), rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
                req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
                req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
                using (var c = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(30)))
                    bytes = c.Export(X509ContentType.Pfx, newPass);
            }
            Directory.CreateDirectory(dataDir);
            File.WriteAllBytes(pfx, bytes);
            File.WriteAllText(key, Crypto.Protect(newPass));
            return Load(bytes, newPass);
        }

        // a PFX loaded this way keeps its key in a temporary container, which SChannel (SslStream's server side) can use
        static X509Certificate2 Load(byte[] bytes, string pass) => new X509Certificate2(bytes, pass, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);

        /// <summary>SHA-256 of the DER certificate, 64 lowercase hex: the fingerprint the Thor pins (and mDNS TXT "fp")</summary>
        public static string Fingerprint(X509Certificate c)
        {
            using (var sha = SHA256.Create()) return Crypto.Hex(sha.ComputeHash(c.GetRawCertData()));
        }
    }
}
