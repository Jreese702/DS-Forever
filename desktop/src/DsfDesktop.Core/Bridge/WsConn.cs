using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace DsfDesktop.Bridge
{
    /// <summary>
    /// A minimal RFC 6455 WebSocket over any Stream: text messages, ping/pong, close; continuation frames reassembled. .NET Framework
    /// has no server WebSocket that works without HttpListener (whose LAN prefixes need a URL ACL, a system setting), hence this.
    /// isClient = mask outgoing frames (the tests' client side, and the RFC's rule for clients).
    /// </summary>
    public sealed class WsConn : IDisposable
    {
        public const int MaxMessage = 1 << 20;
        const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        readonly Stream s;
        readonly bool isClient;
        readonly object sendLock = new object();
        public bool Closed { get; private set; }
        public int CloseCode { get; private set; }

        public WsConn(Stream s, bool isClient = false)
        {
            this.s = s;
            this.isClient = isClient;
        }

        public static string AcceptKey(string key)
        {
            using (var sha = SHA1.Create()) return Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key.Trim() + Guid)));
        }

        public void SendText(string text) => Send(0x1, Encoding.UTF8.GetBytes(text));
        public void SendPing() => Send(0x9, new byte[0]);

        public void Send(int opcode, byte[] payload)
        {
            lock (sendLock)
            {
                if (Closed) throw new IOException("closed");
                var h = new List<byte>(14) { (byte)(0x80 | opcode) };
                byte maskBit = (byte)(isClient ? 0x80 : 0);
                int n = payload.Length;
                if (n < 126) h.Add((byte)(maskBit | n));
                else if (n <= 0xffff) { h.Add((byte)(maskBit | 126)); h.Add((byte)(n >> 8)); h.Add((byte)n); }
                else
                {
                    h.Add((byte)(maskBit | 127));
                    for (int i = 7; i >= 0; i--) h.Add((byte)((long)n >> (8 * i)));
                }
                byte[] body = payload;
                if (isClient)
                {
                    var mask = Crypto.RandomBytes(4);
                    h.AddRange(mask);
                    body = new byte[n];
                    for (int i = 0; i < n; i++) body[i] = (byte)(payload[i] ^ mask[i & 3]);
                }
                var frame = new byte[h.Count + body.Length];
                h.CopyTo(frame);
                Buffer.BlockCopy(body, 0, frame, h.Count, body.Length);
                s.Write(frame, 0, frame.Length);
                s.Flush();
            }
        }

        void ReadExactly(byte[] buf, int n)
        {
            int got = 0;
            while (got < n)
            {
                int r = s.Read(buf, got, n - got);
                if (r <= 0) throw new EndOfStreamException();
                got += r;
            }
        }

        /// <summary>the next text message, or null when the peer closed (or the stream ended)</summary>
        public string ReadText()
        {
            var msg = new MemoryStream();
            int msgOpcode = -1;
            var hdr = new byte[8];
            try
            {
                while (true)
                {
                    ReadExactly(hdr, 2);
                    bool fin = (hdr[0] & 0x80) != 0;
                    int op = hdr[0] & 0x0f;
                    bool masked = (hdr[1] & 0x80) != 0;
                    long len = hdr[1] & 0x7f;
                    if (len == 126) { ReadExactly(hdr, 2); len = (hdr[0] << 8) | hdr[1]; }
                    else if (len == 127) { ReadExactly(hdr, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | hdr[i]; }
                    if (len > MaxMessage || msg.Length + len > MaxMessage) { Close(1009); return null; }
                    var mask = new byte[4];
                    if (masked) ReadExactly(mask, 4);
                    var data = new byte[len];
                    ReadExactly(data, (int)len);
                    if (masked) for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];

                    if (op == 0x8)
                    {
                        CloseCode = data.Length >= 2 ? (data[0] << 8) | data[1] : 1005;
                        Close(1000);
                        return null;
                    }
                    if (op == 0x9) { try { Send(0xA, data); } catch (Exception) { } continue; }
                    if (op == 0xA) continue;
                    if (op == 0x1 || op == 0x2) { msgOpcode = op; msg.SetLength(0); }
                    else if (op != 0x0 || msgOpcode < 0) { Close(1002); return null; }
                    msg.Write(data, 0, data.Length);
                    if (!fin) continue;
                    if (msgOpcode == 0x2) { msgOpcode = -1; msg.SetLength(0); continue; }   // binary: not part of this protocol
                    return Encoding.UTF8.GetString(msg.ToArray());
                }
            }
            catch (Exception)
            {
                MarkClosed();
                return null;
            }
        }

        void MarkClosed()
        {
            lock (sendLock) Closed = true;
            try { s.Close(); } catch (Exception) { }
        }

        public void Close(int code = 1000)
        {
            lock (sendLock)
            {
                if (Closed) return;
                try { Sendraw(code); } catch (Exception) { }
                Closed = true;
            }
            try { s.Close(); } catch (Exception) { }
        }

        void Sendraw(int code)
        {
            var payload = new[] { (byte)(code >> 8), (byte)code };
            var frame = isClient
                ? new byte[] { 0x88, 0x82, 0, 0, 0, 0, payload[0], payload[1] }   // zero mask
                : new byte[] { 0x88, 0x02, payload[0], payload[1] };
            s.Write(frame, 0, frame.Length);
            s.Flush();
        }

        public void Dispose() => Close();
    }
}
