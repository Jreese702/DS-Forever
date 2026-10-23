using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace DsfDesktop.Pairing
{
    public enum TokenCheck { Ok, Bad, Revoked }

    public sealed class Device
    {
        /// <summary>the app's client id (32 hex, made once per app install)</summary>
        public string Id { get; set; }
        /// <summary>SHA-256 of the app's Moonlight client certificate, as the app reported it at pairing (informative)</summary>
        public string ClientCert { get; set; }
        public string Name { get; set; }
        /// <summary>SHA-256 (hex) of the bridge token: the token itself is never stored</summary>
        public string TokenHash { get; set; }
        public long PairedMs { get; set; }
        public long LastSeenMs { get; set; }
        /// <summary>true once Sunshine reported the Moonlight pairing done for this device</summary>
        public bool StreamPaired { get; set; }
    }

    sealed class DeviceFile
    {
        public List<Device> Devices { get; set; } = new List<Device>();
        /// <summary>client ids removed in Desktop: their next hello is told "revoked" (4403), not just "bad token"</summary>
        public List<string> Revoked { get; set; } = new List<string>();
    }

    /// <summary>The paired devices and their bridge tokens, in DataDir\devices.json. Thread-safe.</summary>
    public sealed class DeviceStore
    {
        readonly string file;
        readonly object gate = new object();
        List<Device> devices = new List<Device>();
        List<string> revoked = new List<string>();
        public event Action Changed;

        public DeviceStore(string file)
        {
            this.file = file;
            Load();
        }

        public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        void Load()
        {
            try
            {
                if (file == null || !File.Exists(file)) return;
                var f = new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<DeviceFile>(File.ReadAllText(file));
                if (f?.Devices != null) devices = f.Devices.Where(d => !string.IsNullOrEmpty(d.Id) && !string.IsNullOrEmpty(d.TokenHash)).ToList();
                if (f?.Revoked != null) revoked = f.Revoked;
            }
            catch (Exception e) { Log.I("devices.json unreadable, starting empty: " + e.Message); }
        }

        void Save()
        {
            if (file == null) return;
            Directory.CreateDirectory(Path.GetDirectoryName(file));
            var tmp = file + ".tmp";
            File.WriteAllText(tmp, Json.Write(new DeviceFile { Devices = devices, Revoked = revoked }));
            if (File.Exists(file)) File.Replace(tmp, file, null);
            else File.Move(tmp, file);
        }

        void Fire() { try { Changed?.Invoke(); } catch (Exception) { } }

        public List<Device> All() { lock (gate) return devices.Select(Copy).ToList(); }

        static Device Copy(Device d) => new Device { Id = d.Id, ClientCert = d.ClientCert, Name = d.Name, TokenHash = d.TokenHash, PairedMs = d.PairedMs, LastSeenMs = d.LastSeenMs, StreamPaired = d.StreamPaired };

        public static string Clean(string s, int max)
        {
            s = (s ?? "").Trim();
            var chars = s.Where(c => !char.IsControl(c)).Take(max).ToArray();
            return new string(chars);
        }

        /// <summary>a new token for the device (a re-pair replaces the old one). Returns the token, once.</summary>
        public string Issue(string id, string name, string clientCert = null)
        {
            id = Clean(id, 128);
            if (id.Length == 0) throw new ArgumentException("device id");
            var token = Crypto.Base64Url(Crypto.RandomBytes(32));
            lock (gate)
            {
                devices.RemoveAll(d => d.Id == id);
                revoked.Remove(id);
                devices.Add(new Device { Id = id, ClientCert = Clean(clientCert, 128), Name = Clean(name, 64).Length > 0 ? Clean(name, 64) : "Device", TokenHash = Crypto.Sha256Hex(token), PairedMs = NowMs(), LastSeenMs = NowMs() });
                Save();
            }
            Fire();
            return token;
        }

        public bool Validate(string id, string token) => Check(id, token) == TokenCheck.Ok;

        public TokenCheck Check(string id, string token)
        {
            if (string.IsNullOrEmpty(id)) return TokenCheck.Bad;
            var h = Crypto.Sha256Hex(token ?? "");
            lock (gate)
            {
                var d = devices.FirstOrDefault(x => x.Id == id);
                if (d != null && !string.IsNullOrEmpty(token) && Crypto.FixedTimeEquals(d.TokenHash, h)) return TokenCheck.Ok;
                return d == null && revoked.Contains(id) ? TokenCheck.Revoked : TokenCheck.Bad;
            }
        }

        public void Touch(string id)
        {
            lock (gate)
            {
                var d = devices.FirstOrDefault(x => x.Id == id);
                if (d == null) return;
                d.LastSeenMs = NowMs();
                try { Save(); } catch (Exception) { }
            }
            Fire();
        }

        public void SetStreamPaired(string id, bool v)
        {
            lock (gate)
            {
                var d = devices.FirstOrDefault(x => x.Id == id);
                if (d == null) return;
                d.StreamPaired = v;
                Save();
            }
            Fire();
        }

        public bool Remove(string id)
        {
            bool r;
            lock (gate)
            {
                r = devices.RemoveAll(d => d.Id == id) > 0;
                if (r)
                {
                    if (!revoked.Contains(id)) revoked.Add(id);
                    if (revoked.Count > 200) revoked.RemoveAt(0);
                    Save();
                }
            }
            if (r) Fire();
            return r;
        }
    }
}
