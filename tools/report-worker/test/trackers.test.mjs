// node --test (no packages): known crash types -> a comment on their tracker issue, the limits, the comment's privacy
import test from "node:test";
import assert from "node:assert/strict";
import { classify, buildComment, ramTier, crashingFrames, TRACKERS, TYPED_MAX } from "../src/trackers.js";
import { handle, safe, validate, deviceKey, LIMITS } from "../src/worker.js";

const DIAG = ["=== App ===", "DS Forever 1.0.33 (1482), package app.dsforever.companion", "=== Device ===",
  "RAM: 3248 MB free of 7191 MB, low at 216 MB", "low-memory graphics on",
  "Exits Android recorded (newest first): 2",
  "  2026-11-17 13:52:42 (12 min ago): LOW_MEMORY (status 0, importance 100, process main, pss 1256 MB): lmk",
  "  2026-11-16 10:00:00 (1 days ago): USER_REQUESTED (status 0, importance 400, process main, pss 10 MB)",
  "seen by a@b.co from 10.1.2.3"].join("\n");

const NATIVE = (lib) => [
  "log 11-17 14:05:41.166 I WCCASC : retrying damaged local entry from loose cache",
  "", "===== crashes/crash-2026-11-17-135242.txt =====",
  "Reason: native-crash (5), status 11", "", "---------- Trace ----------", "Native crash (the tombstone, decoded)",
  "Signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault address 0x8d80", "",
  'The crashing thread: tid 17980 "kit-pack-2"',
  `  #00 pc 000000000004fc98  /data/app/~~lockrKNmEeQfNbgbQ96Vlw==/app.dsforever.companion-tSddC3uMqX2ISqi295JiNw==/lib/arm64/${lib}`,
  `  #01 pc 000000000005b68c  /data/app/~~lockrKNmEeQfNbgbQ96Vlw==/app.dsforever.companion-tSddC3uMqX2ISqi295JiNw==/lib/arm64/${lib}`,
  "", "===== crashes/crash-2026-11-10-100000.txt =====", "Reason: native-crash (5)", "The crashing thread: tid 1",
  "  #00 pc 0000 /system/lib64/libc.so",
].join("\n");

const ANR = ["log tail", "===== crashes/anr-2026-11-12.txt =====", "Reason: anr (6)", "---------- Trace ----------",
  '"main" prio=5 tid=1 Blocked', "  at com.jrees.wowcompanion.MapView.onDraw(MapView.kt:812)", "  - waiting to lock <0x0a1b>"].join("\n");

const report = (words, extra = {}) => ({
  version: "1.0.33 (1482)", addons: "set 399", device: "AYN AYN Thor, Android 13", words,
  report: "DS Forever problem report", logs: "log line", diag: DIAG, recorded: false, ...extra,
});
const AUTO = "Automatic crash report: ";
const LOWMEM = AUTO + "Android closed DS Forever because the device ran low on memory. The game was running (in the world, 12 min in).";

const ids = (r) => classify(validate(r).report)?.id ?? null;

test("the known types, and what stays unknown", () => {
  assert.equal(ids(report(LOWMEM)), "low-memory-kill");
  assert.equal(ids(report(AUTO + "DS Forever stopped responding. The game was running (still loading, 1 min in).", { logs: ANR })), "anr");
  assert.equal(ids(report(AUTO + "DS Forever crashed (native code).", { logs: NATIVE("libcasc.so") })), "libcasc-native-crash");
  // a native crash elsewhere (the newest record's crashing thread is not libcasc; an OLDER record's libc does not decide it either)
  assert.equal(ids(report(AUTO + "DS Forever crashed (native code).", { logs: NATIVE("libfoo.so") })), null);
  assert.equal(ids(report(AUTO + "DS Forever crashed (native code).")), null);                    // no record at all
  assert.equal(ids(report(AUTO + "DS Forever crashed. The game was running (in the world, 3 min in).")), null);
  assert.equal(ids(report(AUTO + "DS Forever was stopped by a signal.")), null);
  // typed: the sign-in waves (the titles of #237 #485 #423 #118 #105, and #165 #235 #271 #438 #527)
  for (const w of ["cannot connect, error BLZ51914400", "cannot login with blz51914400 error", "Dont log in (BLZ51914400)",
    "cant get passed  captcha errorblz51914400", "errror whn loggin in error codeblz51914400"]) assert.equal(ids(report(w)), "blz51914400-sign-in", w);
  for (const w of ["Cant login. I'm getting a CAPCA test then an error after typing in.", "Cant login, dc on authenticator code into captcha dc",
    "i cannot get past the captcha", "cannot login captcha error loop", "try to enter code from authenticator and just keeps telling me disconnected",
    "instant disconnection at login authentication"]) assert.equal(ids(report(w)), "captcha-authenticator-disconnect", w);
  // not these: other problems, a long description, a report recorded in detail, an "automatic" one of the sign-in words
  for (const w of ["the world map pictures wont load", "you have been disconnected", "game says disconnected no matter what",
    "the captcha screen looks great"]) assert.equal(ids(report(w)), null, w);
  assert.equal(ids(report("BLZ51914400 " + "and much more about the bags room ".repeat(10))), null);
  assert.ok("BLZ51914400 ".length + 340 > TYPED_MAX);
  assert.equal(ids(report("cannot connect, error BLZ51914400", { recorded: true })), null);
  assert.equal(ids(report(AUTO + "BLZ51914400")), null);
  assert.equal(new Set(TRACKERS.map((t) => t.issue)).size, TRACKERS.length);
});

test("the crashing thread's frames and the RAM tier", () => {
  assert.equal(crashingFrames(NATIVE("libcasc.so")).length, 2);
  assert.deepEqual(crashingFrames("no record"), []);
  assert.equal(ramTier(DIAG), "8 GB");
  assert.equal(ramTier("RAM: 1 MB free of 11265 MB, low at 1 MB"), "12 GB");
  assert.equal(ramTier("RAM: 1 MB free of 15255 MB, low at 1 MB"), "16 GB");
  assert.equal(ramTier(""), "?");
});

test("the comment: short, the signature and the facts, never the logs or anything personal", () => {
  const r = validate(report(LOWMEM + " Mail me at a@b.co, @someone", { logs: "SECRET LOG LINE " + NATIVE("libcasc.so") })).report;
  const t = classify(r);
  const c = buildComment(r, t, safe, 3);
  assert.ok(c.includes("`low-memory-kill`"));
  assert.ok(c.includes("| 1.0.33 (1482) | set 399 | AYN AYN Thor, Android 13 | 8 GB |"));
  assert.ok(c.includes("> Automatic crash report: Android closed DS Forever"));
  assert.ok(c.includes("RAM: 3248 MB free of 7191 MB") && c.includes(": LOW_MEMORY (status 0"), "the key lines");
  assert.ok(!c.includes("USER_REQUESTED"), "only the low-memory exits");
  assert.ok(c.includes("_3 more reports of this since the last comment"));
  assert.ok(!c.includes("SECRET LOG LINE") && !c.includes("a@b.co") && !c.includes("10.1.2.3") && !/@someone/.test(c));
  assert.ok(c.length < 3_000, `${c.length}`);
  // the libcasc crash's key lines: the signal and the frames, without the install folder's random names
  const n = validate(report(AUTO + "DS Forever crashed (native code).", { logs: NATIVE("libcasc.so") })).report;
  const nc = buildComment(n, classify(n), safe);
  assert.ok(nc.includes("Signal 11 (SIGSEGV)") && nc.includes("#00 pc 000000000004fc98 lib/arm64/libcasc.so"));
  assert.ok(!nc.includes("/data/app/") && !nc.includes("more report"));
  // the ANR's main thread
  const a = validate(report(AUTO + "DS Forever stopped responding.", { logs: ANR })).report;
  assert.ok(buildComment(a, classify(a), safe).includes("MapView.onDraw"));
});

// ------------------------------------------------------------------ the receiver end to end (GitHub and KV faked)

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.get(k) ?? null; }
  async put(k, v) { this.m.set(k, v); }
}
const env = (extra = {}) => ({ GITHUB_TOKEN: "t", GITHUB_REPO: "Jreese702/DS-Forever", APP_KEY: "k", LIMITS: new FakeKV(), ...extra });
const req = (body, ip = "203.0.113.9") => new Request("https://r.example/report", {
  method: "POST", body: JSON.stringify(body), headers: { "Content-Type": "application/json", "X-DSF-Key": "k", "CF-Connecting-IP": ip },
});
function fakeGitHub(calls, { commentStatus = 201 } = {}) {
  globalThis.fetch = async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body) });
    if (url.endsWith("/issues")) return new Response(JSON.stringify({ number: 600, html_url: "https://github.com/x/issues/600" }), { status: 201 });
    if (url.includes("/comments")) {
      if (commentStatus !== 201) return new Response('{"message":"Issue is locked"}', { status: commentStatus });
      return new Response(JSON.stringify({ html_url: `${url.replace("api.github.com/repos", "github.com")}#c1` }), { status: 201 });
    }
    return new Response("{}", { status: 500 });
  };
}
const NOW = Date.UTC(2026, 10, 18, 9);

test("a known type comments on its tracker; the app hears the tracker's number", async () => {
  const calls = []; fakeGitHub(calls);
  const res = await handle(req(report(LOWMEM)), env(), NOW);
  assert.equal(res.status, 201);
  const out = await res.json();
  assert.equal(out.number, 56);
  assert.equal(out.tracker, "low-memory-kill");
  assert.equal(out.commented, true);
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.endsWith("/repos/Jreese702/DS-Forever/issues/56/comments"));
  assert.ok(calls[0].body.body.includes("`low-memory-kill`"));
});

test("the limits: one per device per tracker a day, a few per tracker an hour, the rest counted and named in the next comment", async () => {
  const calls = []; fakeGitHub(calls);
  const e = env();
  assert.equal((await handle(req(report(LOWMEM), "198.51.100.1"), e, NOW)).status, 201);
  // the same device again that day: counted, no GitHub call, still the tracker's number
  const again = await handle(req(report(LOWMEM), "198.51.100.1"), e, NOW + 3 * 3_600_000);
  assert.equal(again.status, 200);
  assert.deepEqual(await again.json(), { number: 56, url: "https://github.com/Jreese702/DS-Forever/issues/56", tracker: "low-memory-kill", commented: false });
  assert.equal(calls.length, 1);
  // the same device on ANOTHER tracker: its own comment
  assert.equal((await handle(req(report("cannot connect, error BLZ51914400"), "198.51.100.1"), e, NOW)).status, 201);
  assert.equal(calls.length, 2);
  // other devices in one hour: perTrackerPerHour comments, then counted
  calls.length = 0;
  const hour = NOW + 5 * 3_600_000;
  for (let i = 0; i < LIMITS.perTrackerPerHour + 3; i++) await handle(req(report(LOWMEM), `192.0.2.${i}`), e, hour);
  assert.equal(calls.length, LIMITS.perTrackerPerHour);
  // the next hour's comment says how many were only counted since the LAST comment (the 3 over the hour's cap; the same-device
  // one earlier was before the hour's first comment)
  calls.length = 0;
  assert.equal((await handle(req(report(LOWMEM), "192.0.2.200"), e, hour + 3_600_000)).status, 201);
  assert.ok(calls[0].body.body.includes("_3 more reports of this since the last comment"), calls[0].body.body);
  // and the one after starts again from none
  calls.length = 0;
  await handle(req(report(LOWMEM), "192.0.2.201"), e, hour + 3_600_000);
  assert.ok(!calls[0].body.body.includes("more report"));
  // the next day the first device comments again
  calls.length = 0;
  assert.equal((await handle(req(report(LOWMEM), "198.51.100.1"), e, NOW + 86_400_000)).status, 201);
  assert.equal(calls.length, 1);
  // no address in any key
  assert.ok([...e.LIMITS.m.keys()].filter((k) => !k.startsWith("ip:")).every((k) => !k.includes("198.51") && !k.includes("192.0.2")));
  assert.equal(await deviceKey("1.2.3.4", "x"), await deviceKey("1.2.3.4", "x"));
  assert.notEqual(await deviceKey("1.2.3.4", "x"), await deviceKey("1.2.3.4", "y"));
});

test("unknown types, a switched-off routing and a failed comment open an issue as before", async () => {
  const calls = []; fakeGitHub(calls);
  let res = await handle(req(report(AUTO + "DS Forever crashed.")), env(), NOW);
  assert.equal(res.status, 201);
  assert.equal((await res.json()).number, 600);
  assert.ok(calls[0].url.endsWith("/issues"));
  calls.length = 0;
  res = await handle(req(report(LOWMEM)), env({ TRACKERS: "off" }), NOW);
  assert.equal((await res.json()).number, 600);
  assert.equal(calls.length, 1);
  // a locked tracker: the comment fails, the report becomes its own issue (nothing lost), counted once in the day's total
  const failing = []; fakeGitHub(failing, { commentStatus: 403 });
  const e = env();
  res = await handle(req(report(LOWMEM)), e, NOW);
  assert.equal(res.status, 201);
  assert.equal((await res.json()).number, 600);
  assert.equal(failing.length, 2);
  assert.equal(e.LIMITS.m.get(`all:${Math.floor(NOW / 86_400_000)}`), "1");
});

test("the per-address limit still holds for known types; counted reports do not use the day's GitHub budget", async () => {
  fakeGitHub([]);
  const e = env();
  for (let i = 0; i < LIMITS.perIpPerHour; i++) await handle(req(report(LOWMEM)), e, NOW);
  assert.equal((await handle(req(report(LOWMEM)), e, NOW)).status, 429);
  assert.equal(e.LIMITS.m.get(`all:${Math.floor(NOW / 86_400_000)}`), "1", "one comment, four only counted");
});

test("memory work #56 (D5): a low-memory comment quotes the killed run's memory in short, after the exits", () => {
  const mem = ["Mem stamp: dsf 1763600000 pss 390 j 31 n 210 gx 40 game 4120 free 216 gpu 880 | scenes 41 kit 6",
    "Mem event: 18:44:10 relief (world): let go voice 300 MB, safepath 24 MB; java 140 -> 31 MB; psi some 2.10 full 0.40",
    "Mem line: 18:50:03 pss 380 (j 30 n 205 gx 40) game 4100 free 260 | scenes 41 kit 6",
    "Mem line: 18:50:08 pss 385 (j 31 n 207 gx 40) game 4110 free 230 | scenes 41 kit 6",
    "Mem line: 18:50:13 pss 390 (j 31 n 210 gx 40) game 4120 free 216 | scenes 41 kit 6"];
  const diag = DIAG + "\n" + mem.join("\n");
  const r = validate(report(LOWMEM, { diag })).report;
  const c = buildComment(r, classify(r), safe, 0);
  assert.ok(c.includes(": LOW_MEMORY (status 0"), "the exits still");
  for (const l of mem) assert.ok(c.includes(l), l);
  assert.ok(c.indexOf("LOW_MEMORY (status 0") < c.indexOf("Mem stamp: "), "the exits first");
  // an older app's diagnostics: no memory lines, the comment as before
  const old = buildComment(validate(report(LOWMEM)).report, classify(validate(report(LOWMEM)).report), safe, 0);
  assert.ok(!old.includes("Mem "));
});
