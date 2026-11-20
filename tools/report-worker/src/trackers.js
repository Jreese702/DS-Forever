// KNOWN CRASH TYPES GO TO THEIR TRACKER ISSUE (the user, 2026-11-18: "Comment on the tracker"). Most automatic crash reports were
// the same crash again (187 of them low-memory kills, duplicates of tracker #56), each one a new issue. A report whose signature is a
// known type now adds a SHORT comment to that type's tracker issue instead; an unknown type still opens its own issue.
//
// The classification is here, in the receiver, so every app version that already sends reports benefits without an update.
// Nothing in a comment is more than the issue would have shown, and much less: the version, the device model, its RAM tier, the
// signature, the player's (already redacted, scrubbed again) words and a few key lines - never logs.txt, never names.

/** how many characters of the player's words a comment quotes */
export const COMMENT_WORDS = 400;
/** a typed report longer than this describes more than the known problem: it keeps its own issue */
export const TYPED_MAX = 300;
/** at most this many key lines in a comment */
export const KEY_LINES = 12;
/** the memory lines a low-memory comment quotes at most (memory work #56: the stamp, 3 samples, 6 trims and reliefs) */
export const MEM_KEY_LINES = 10;

const AUTO = "Automatic crash report: ";

/**
 * THE KNOWN TYPES. `auto` = the app's own automatic crash report (LastRun's words after "Automatic crash report: ", always English);
 * `typed` = a short report the player typed (never one recorded in detail); `check` = a further test on the whole report.
 * Order matters: the first match wins.
 */
export const TRACKERS = [
  {
    id: "low-memory-kill", issue: 56,
    auto: /^Android closed DS Forever because the device ran low on memory\b/,
    // memory work #56 (D5): then the killed run's memory in short (the app's StartupDiag.memKeyLines: the exit record's stamp, the
    // last 3 memory lines, the last trims and reliefs); an older app sends none
    maxKeys: 22,
    keys: (r) => [
      ...diagLines(r.diag, [/^RAM: /, /^low-memory graphics /, /^Exits Android recorded/, /^ {2}\S.*: LOW_MEMORY /]),
      ...diagLines(r.diag, [/^Mem (stamp|line|event): /], MEM_KEY_LINES),
    ],
  },
  {
    id: "anr", issue: 153,
    auto: /^DS Forever stopped responding\b/,
    keys: (r) => traceLines(r.logs, KEY_LINES),
  },
  {
    // the button-art (kit) build crashing inside libcasc.so (#472): the newest crash record's crashing thread is in libcasc
    id: "libcasc-native-crash", issue: 472,
    auto: /^DS Forever crashed \(native code\)/,
    check: (r) => crashingFrames(r.logs).some((f) => f.includes("libcasc.so")),
    keys: (r) => nativeLines(r.logs),
  },
  {
    id: "blz51914400-sign-in", issue: 237,
    typed: /blz\s*-?\s*5191\s*4400|(?<!\d)51914400(?!\d)/i,
  },
  {
    // a captcha or authenticator step that ends in a disconnect, an error or no way in (#165, #235, #271, #438, #527 ...)
    id: "captcha-authenticator-disconnect", issue: 165,
    typed: /capt?ch?a|capca|authenticat|auth(?:enticator)? code/i,
    check: (r) => /disconn|\bdc\b|error|fail|loop|can'?t|cannot|\bnot\b|\bno\b|won'?t|stuck|keeps/i.test(r.words),
  },
];

/** the newest crash record's part of logs.txt (the app puts the records last, newest first: ReportUpload.request) */
function newestRecord(logs) {
  const i = logs.indexOf("\n===== crashes/");
  if (i < 0) return "";
  const next = logs.indexOf("\n===== ", i + 2);
  return next < 0 ? logs.slice(i) : logs.slice(i, next);
}

/** the frames (#00 ...) under the newest native crash's "The crashing thread" line, at most [n] */
export function crashingFrames(logs, n = 6) {
  const rec = newestRecord(logs || "");
  const at = rec.indexOf("The crashing thread");
  if (at < 0) return [];
  return rec.slice(at).split("\n").slice(1).map((l) => l.trim()).filter((l) => /^#\d+ /.test(l)).slice(0, n);
}

/**
 * a frame without the install folder's random names ("/data/app/~~x==/pkg-y==/lib/arm64/libcasc.so" -> "lib/arm64/libcasc.so",
 * ".../base.apk" -> "base.apk") and without the BuildId
 */
const shortFrame = (f) => f.replace(/\/data\/app\/[^\s/]*\/[^\s/]*\//, "").replace(/ \(BuildId: [0-9a-f]+\)/, "").replace(/\s+/g, " ");

function nativeLines(logs) {
  const rec = newestRecord(logs || "");
  const signal = rec.split("\n").find((l) => /^Signal \d+/.test(l.trim()));
  const thread = rec.split("\n").find((l) => l.startsWith("The crashing thread"));
  return [signal, thread, ...crashingFrames(logs).map(shortFrame)].filter(Boolean).map((l) => l.trim());
}

/**
 * an ANR in short: the record's Description (what timed out), then the main thread's name and its first frames (LogSieve.traceFirst
 * puts the main thread first), the "| ..." state lines left out
 */
function traceLines(logs, n) {
  const rec = newestRecord(logs || "");
  const desc = rec.split("\n").find((l) => l.startsWith("Description: "));
  const at = rec.indexOf("---------- Trace");
  const trace = at < 0 ? [] : rec.slice(at).split("\n").slice(1).map((l) => l.trim())
    .filter((l) => /^"main"|^native: #\d+|^at |^- (waiting|locked|sleeping)/.test(l)).map(shortFrame);
  return [desc && desc.slice(0, 240), ...trace].filter(Boolean).slice(0, n);
}

/** the diagnostics' lines matching any of [res], in order, at most [max] */
function diagLines(diag, res, max = KEY_LINES) {
  return (diag || "").split("\n").filter((l) => res.some((re) => re.test(l))).slice(0, max).map((l) => l.trimEnd());
}

/** the known type of [r] (a validated report), or null = an unknown type (its own issue) */
export function classify(r) {
  const words = (r.words || "").trim();
  const auto = words.startsWith(AUTO) ? words.slice(AUTO.length) : null;
  for (const t of TRACKERS) {
    if (t.auto) { if (auto === null || !t.auto.test(auto)) continue; }
    else if (t.typed) { if (auto !== null || r.recorded || words.length > TYPED_MAX || !t.typed.test(words)) continue; }
    if (t.check && !t.check(r)) continue;
    return t;
  }
  return null;
}

/** the device's RAM tier from the diagnostics' "RAM: ... free of N MB" (DeviceTier's bounds), "?" = no diagnostics (an older app) */
export function ramTier(diag) {
  const m = /^RAM: \d+ MB free of (\d+) MB/m.exec(diag || "");
  if (!m) return "?";
  const mb = parseInt(m[1], 10);
  if (mb < 9_500) return "8 GB";
  if (mb < 13_000) return "12 GB";
  if (mb < 17_500) return "16 GB";
  return `${Math.round(mb / 1024)} GB`;
}

/**
 * THE COMMENT: one table row, the words quoted, a few key lines collapsed. [safe] = the receiver's scrub + markdown guard;
 * [uncommented] = reports of this type counted since the last comment without one of their own (the limits).
 */
export function buildComment(r, t, safe, uncommented = 0) {
  const words = safe(r.words.trim()).slice(0, COMMENT_WORDS);
  const keys = (t.keys ? t.keys(r) : []).map((l) => safe(l).slice(0, 240)).slice(0, t.maxKeys || KEY_LINES);
  return [
    `**Another report** of \`${t.id}\``,
    "",
    `| DS Forever | Add-ons | Device | RAM |`,
    `|---|---|---|---|`,
    `| ${safe(r.version)} | ${safe(r.addons)} | ${safe(r.device).replace(/\|/g, "/")} | ${ramTier(r.diag)} |`,
    "",
    words.split("\n").map((l) => `> ${l}`).join("\n"),
    ...(keys.length ? ["", "<details><summary>Key lines</summary>", "", "```text", ...keys, "```", "</details>"] : []),
    ...(uncommented > 0 ? ["", `_${uncommented} more report${uncommented === 1 ? "" : "s"} of this since the last comment, counted without one (the limits: one per device a day, a few an hour)._`] : []),
    "",
  ].join("\n");
}
