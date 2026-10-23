using System;
using System.Collections.Generic;
using System.Globalization;
using System.Web.Script.Serialization;

namespace DsfDesktop
{
    /// <summary>JSON through the serializer that ships in .NET Framework (no NuGet). Objects come back as Dictionary&lt;string, object&gt;.</summary>
    public static class Json
    {
        static JavaScriptSerializer Make() => new JavaScriptSerializer { MaxJsonLength = int.MaxValue, RecursionLimit = 256 };

        public static string Write(object o) => Make().Serialize(o);

        public static object Read(string s) => Make().DeserializeObject(s);

        /// <summary>the object, or null when the text is not a JSON object</summary>
        public static Dictionary<string, object> ReadObject(string s)
        {
            try { return Make().DeserializeObject(s) as Dictionary<string, object>; }
            catch (Exception) { return null; }
        }

        public static string Str(this Dictionary<string, object> d, string k)
            => d != null && d.TryGetValue(k, out var v) && v != null ? Convert.ToString(v, CultureInfo.InvariantCulture) : null;

        public static long Long(this Dictionary<string, object> d, string k, long dflt = 0)
        {
            if (d == null || !d.TryGetValue(k, out var v) || v == null) return dflt;
            try { return Convert.ToInt64(v, CultureInfo.InvariantCulture); } catch (Exception) { return dflt; }
        }

        public static bool Bool(this Dictionary<string, object> d, string k)
            => d != null && d.TryGetValue(k, out var v) && v is bool b && b;
    }
}
