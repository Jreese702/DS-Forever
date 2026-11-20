using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using DsfDesktop.Wow;
using DsfDesktop.Wow.Patch;
using static DsfDesktop.Tests.PatchKit;

namespace DsfDesktop.Tests
{
    /// <summary>Blizzard over HTTPS (2026-11-20, after WoW Forever for Android v2.3.7 7d0b00d0 and v2.3.9 efa6e700): the patch
    /// server, the CDN hosts' HTTPS names, and a host whose certificate fails skipped (Korea's blizzard.gcdn.cloudn.co.kr)</summary>
    public static class PatchHttpsTests
    {
        /// <summary>answers by host: a refused certificate for [bad], else [answer]; every asked URL kept</summary>
        sealed class Hosts : HttpMessageHandler
        {
            public readonly List<string> Asked = new List<string>();
            public string Bad;
            public Func<HttpRequestMessage, HttpResponseMessage> Answer = r => new HttpResponseMessage(HttpStatusCode.NotFound);
            protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
            {
                lock (Asked) Asked.Add(request.RequestUri.ToString());
                if (request.RequestUri.Host == Bad)
                    throw new HttpRequestException("An error occurred while sending the request.",
                        new WebException("Could not establish trust relationship for the SSL/TLS secure channel.",
                            new System.Security.Authentication.AuthenticationException("The remote certificate is invalid"), WebExceptionStatus.TrustFailure, null));
                return Task.FromResult(Answer(request));
            }
        }

        static Exception Threw(Action a) { try { a(); return null; } catch (Exception e) { return e; } }

        [Test]
        public static void ThePatchServerIsHttps()
        {
            A.Eq("https://us.version.battle.net/v2/products/wowf/versions", GameVersion.VersionsUrl("us", "wowf"));
            A.Eq("https://us.version.battle.net/v2/products/wowf/versions", GameVersion.VersionsUrl("kr", "wowf"), "us lists every region");
            var all = GameVersion.VersionsUrls("wowf");
            A.Eq(2, all.Count); A.Eq("https://eu.version.battle.net/v2/products/wowf/versions", all[1]);
            A.True(GameVersion.PatchBases("wowf").All(b => b.StartsWith("https://")), "never plain HTTP");
        }

        [Test]
        public static void TheCdnHostsOverHttps()
        {
            A.Eq("level3.ssl.blizzard.com", CdnClient.HttpsHost("level3.blizzard.com"));
            A.Eq("us.cdn.blizzard.com", CdnClient.HttpsHost("US.cdn.blizzard.com"));
            const string kr = "http://blizzard.gcdn.cloudn.co.kr/?fallback=1 http://kr.cdn.blizzard.com/?maxhosts=2 http://level3.blizzard.com/?maxhosts=4 " +
                "https://blizzard.gcdn.cloudn.co.kr/?maxhosts=4&fallback=1 https://kr.cdn.blizzard.com/?maxhosts=4&fallback=1 https://level3.ssl.blizzard.com/?maxhosts=4&fallback=1";
            A.Eq("blizzard.gcdn.cloudn.co.kr kr.cdn.blizzard.com level3.ssl.blizzard.com", string.Join(" ", CdnClient.HttpsHosts(kr, new[] { "x" })));
            A.Eq("level3.blizzard.com us.cdn.blizzard.com", string.Join(" ", CdnClient.HttpsHosts("http://level3.blizzard.com/?maxhosts=8", new[] { "level3.blizzard.com", "us.cdn.blizzard.com" })),
                "no https server: the hosts (CdnClient gives them their HTTPS names)");
            A.Eq("127.0.0.1:9", string.Join(" ", CdnClient.HttpsHosts(null, new[] { "127.0.0.1:9" })));
        }

        [Test]
        public static void OnlyACertificateFailureCounts()
        {
            A.True(CdnClient.IsCertificateFailure(new AggregateException(new HttpRequestException("x", new WebException("t", null, WebExceptionStatus.TrustFailure, null)))));
            A.True(CdnClient.IsCertificateFailure(new HttpRequestException("x", new System.Security.Authentication.AuthenticationException("bad"))));
            A.False(CdnClient.IsCertificateFailure(new AggregateException(new HttpRequestException("x", new WebException("reset", null, WebExceptionStatus.ConnectionClosed, null)))));
            A.False(CdnClient.IsCertificateFailure(new CdnException("HTTP 503")));
        }

        [Test]
        public static void AHostWhoseCertificateFailsIsSkippedAndNeverHidesA404()
        {
            var h = new Hosts { Bad = "blizzard.gcdn.cloudn.co.kr" };
            var c = new CdnClient(new[] { "blizzard.gcdn.cloudn.co.kr", "kr.cdn.blizzard.com", "level3.blizzard.com" }, "tpr/wow", h);
            var k = Key("loose");
            // a loose client file is normally missing: the refused host is neither a failure nor a "missing" of its own
            A.Eq(null, c.Fetch("data", k));
            A.Eq(3, h.Asked.Count);
            A.True(h.Asked.All(u => u.StartsWith("https://")), "https only: " + string.Join(" ", h.Asked));
            A.True(h.Asked.Any(u => u.StartsWith("https://level3.ssl.blizzard.com/tpr/wow/")), "Level 3 under its HTTPS name");
            // from then on it is not asked
            h.Asked.Clear();
            var body = B("game data");
            h.Answer = r => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(body) };
            A.Eq(Md5(body), Md5(c.Fetch("data", k)));
            A.Eq(1, h.Asked.Count); A.True(h.Asked[0].StartsWith("https://kr.cdn.blizzard.com/"), h.Asked[0]);
        }

        [Test]
        public static void EveryHostRefusedIsAnErrorAndAskedAgain()
        {
            var h = new Hosts { Bad = "us.cdn.blizzard.com" };
            var c = new CdnClient(new[] { "us.cdn.blizzard.com" }, "tpr/wow", h);
            var k = Key("x");
            var e = Threw(() => c.Fetch("data", k));
            A.True(e is CdnException && e.Message.Contains("certificate"), e?.Message ?? "no error");
            Threw(() => c.Fetch("data", k));
            A.Eq(2, h.Asked.Count, "the only host is asked again (a Wi-Fi sign-in page), its certificate still checked");
        }
    }
}
