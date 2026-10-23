using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace DsfDesktop.Bridge
{
    /// <summary>just enough HTTP/1.1 for the bridge: one request line + headers, no body (GETs and the WebSocket upgrade only)</summary>
    public sealed class HttpRequest
    {
        public const int MaxHeader = 16 * 1024;
        public string Method, Target, Path, Query;
        public readonly Dictionary<string, string> Headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public string Header(string k) => Headers.TryGetValue(k, out var v) ? v : null;

        public bool IsUpgrade =>
            (Header("Upgrade") ?? "").Equals("websocket", StringComparison.OrdinalIgnoreCase) &&
            (Header("Connection") ?? "").IndexOf("upgrade", StringComparison.OrdinalIgnoreCase) >= 0 &&
            !string.IsNullOrEmpty(Header("Sec-WebSocket-Key"));

        /// <summary>reads byte by byte up to the blank line, so not one byte of a following WebSocket frame is consumed</summary>
        public static HttpRequest Read(Stream s)
        {
            var buf = new List<byte>(1024);
            while (buf.Count < MaxHeader)
            {
                int b = s.ReadByte();
                if (b < 0) return null;
                buf.Add((byte)b);
                int n = buf.Count;
                if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n')
                    return Parse(Encoding.ASCII.GetString(buf.ToArray()));
            }
            return null;
        }

        public static HttpRequest Parse(string head)
        {
            var lines = head.Replace("\r\n", "\n").Split('\n');
            var first = lines[0].Split(' ');
            if (first.Length < 3) return null;
            var r = new HttpRequest { Method = first[0], Target = first[1] };
            int q = r.Target.IndexOf('?');
            r.Path = q < 0 ? r.Target : r.Target.Substring(0, q);
            r.Query = q < 0 ? "" : r.Target.Substring(q + 1);
            for (int i = 1; i < lines.Length; i++)
            {
                int c = lines[i].IndexOf(':');
                if (c <= 0) continue;
                r.Headers[lines[i].Substring(0, c).Trim()] = lines[i].Substring(c + 1).Trim();
            }
            return r;
        }

        public static void Respond(Stream s, int code, string reason, string contentType, string body)
        {
            var bytes = Encoding.UTF8.GetBytes(body ?? "");
            var head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                       "Content-Type: " + contentType + "\r\n" +
                       "Content-Length: " + bytes.Length + "\r\n" +
                       "Cache-Control: no-store\r\n" +
                       "Connection: close\r\n\r\n";
            var h = Encoding.ASCII.GetBytes(head);
            s.Write(h, 0, h.Length);
            s.Write(bytes, 0, bytes.Length);
            s.Flush();
        }
    }
}
