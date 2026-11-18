// DS Forever's problem-report receiver (board f-problem-report; the user's pick "In-game upload via my own receiver").
//
// A Cloudflare Worker: POST /report takes one report DS Forever already redacted on the device, checks it, limits how often it is
// called, and opens a GitHub issue in the project's repository with a bot token kept as a Worker SECRET (never in git, never in the
// APK). It answers {"number": N, "url": "..."}.
//
// A KNOWN CRASH TYPE (src/trackers.js; the user, 2026-11-18: "Comment on the tracker") opens no issue: it adds a short comment to
// its tracker issue (#56 low-memory kills, #153 "stopped responding", #472 the libcasc art crash, #237 BLZ51914400, #165 the
// captcha / authenticator disconnect), at most one per device per tracker a day and a few per tracker an hour; over that it is only
// counted (the next comment says how many). The answer is then the tracker's number, plus "tracker" and "commented".
//
// Abuse: the app's key (APP_KEY) ships inside the APK, so anyone can extract it; it only keeps casual traffic out. The real limits are
// here: a size cap, a strict shape, a per-address and a global rate limit, and every issue carries the "player-report" label.

import { classify, buildComment } from "./trackers.js";

export const LIMITS = {
  maxBytes: 200_000,          // the whole request
  maxWords: 2_000,            // the player's description
  maxReport: 60_000,          // report.txt
  maxLogs: 150_000,           // logs.txt
  maxDiag: 45_000,            // diagnostics.txt (the app's start-up diagnostics, StartupDiag; optional: older apps send none)
  perIpPerHour: 5,
  globalPerDay: 100,          // what reaches GitHub (new issues and tracker comments); a report only counted does not count here
  issueBody: 65_536,          // GitHub's body limit (characters)
  // the tracker comments (src/trackers.js): one per device per tracker a day, a few per tracker an hour; the rest only counted
  perDevicePerTrackerPerDay: 1,
  perTrackerPerHour: 4,
};

const VERSION = /^\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?(?: \(\d+\))?$/;

/** the report as sent, or a list of what is wrong with it */
export function validate(body) {
  const errors = [];
  if (body === null || typeof body !== "object" || Array.isArray(body)) return { errors: ["not a JSON object"] };
  const str = (k, min, max) => {
    const v = body[k];
    if (typeof v !== "string") { errors.push(`${k}: missing`); return ""; }
    if (v.length < min) errors.push(`${k}: too short`);
    if (v.length > max) errors.push(`${k}: too long (${v.length} > ${max})`);
    return v;
  };
  const report = {
    version: str("version", 5, 40),
    addons: str("addons", 1, 40),
    device: str("device", 3, 120),
    words: str("words", 3, LIMITS.maxWords),
    report: str("report", 1, LIMITS.maxReport),
    logs: typeof body.logs === "string" ? body.logs : "",
    diag: typeof body.diag === "string" ? body.diag : "",
    recorded: body.recorded === true,
  };
  if (report.logs.length > LIMITS.maxLogs) errors.push(`logs: too long (${report.logs.length} > ${LIMITS.maxLogs})`);
  if (report.diag.length > LIMITS.maxDiag) errors.push(`diag: too long (${report.diag.length} > ${LIMITS.maxDiag})`);
  if (report.version && !VERSION.test(report.version)) errors.push("version: not a DS Forever version");
  if (report.words && !/[\p{L}\p{N}]/u.test(report.words)) errors.push("words: nothing said");
  return errors.length ? { errors } : { report };
}

/** a second privacy pass (the app redacts first): e-mails, BattleTags, IP addresses */
export function scrub(s) {
  return s
    .replace(/[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}/g, "<email>")
    .replace(/(?<![\p{L}\p{N}])\p{L}[\p{L}\p{N}]{1,11}#\d{4,6}(?!\d)/gu, "<battletag>")
    .replace(/(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])/g, "<ip>");
}

/** text that cannot break out of a code block or ping anyone: fences softened, @mentions broken */
export function safe(s) {
  return scrub(s).replace(/```/g, "ˋˋˋ").replace(/@(?=[\w-])/g, "@​");
}

/** the issue: title, body (within GitHub's limit), and the rest of the logs as a comment when they do not fit */
export function buildIssue(r) {
  const words = safe(r.words.trim());
  const first = words.split("\n")[0].slice(0, 70) || "DS Forever";
  // the version in the title (the user: the reports must say which build at a glance)
  const title = `Player report (${safe(r.version)}): ${first}`;
  const diag = r.diag ? safe(r.diag).slice(0, LIMITS.maxDiag) : "";
  const top = [
    "**What happened** (the player's words)",
    "",
    words.split("\n").map((l) => `> ${l}`).join("\n"),
    "",
    `| DS Forever | Add-ons | Device | Recorded in detail |`,
    `|---|---|---|---|`,
    `| ${safe(r.version)} | ${safe(r.addons)} | ${safe(r.device).replace(/\|/g, "/")} | ${r.recorded ? "yes" : "no"} |`,
    "",
    // the app's start-up diagnostics (device, game folder, art, start-up stages, its own log), collapsed - always whole in the body
    ...(diag ? ["<details><summary>Diagnostics</summary>", "", "```text", diag, "```", "</details>", ""] : []),
  ].join("\n");
  // report.txt gets what the body has left (keeping some room for the newest log lines); its rest is cut, never the body's limit broken
  const reportRoom = Math.max(2_000, LIMITS.issueBody - top.length - 8_000);
  let report = safe(r.report);
  if (report.length > reportRoom) report = report.slice(0, reportRoom - 40) + "\n[cut: the rest is in the report file]";
  const head = top + "\n" + ["<details><summary>report.txt</summary>", "", "```text", report, "```", "</details>", ""].join("\n");
  const logs = safe(r.logs);
  const open = "<details><summary>logs.txt</summary>\n\n```text\n";
  const close = "\n```\n</details>\n";
  const room = LIMITS.issueBody - head.length - open.length - close.length - 200;
  let body, comment = null;
  if (!logs) body = head;
  else if (logs.length <= room) body = head + open + logs + close;
  else {
    // the newest lines in the issue; the older part as a comment (itself within the limit)
    const keep = Math.max(0, room);
    const cut = logs.length - keep;
    body = head + open + "[older lines in the first comment]\n" + logs.slice(cut) + close;
    const older = logs.slice(0, cut);
    const cRoom = LIMITS.issueBody - open.length - close.length - 200;
    comment = open + (older.length > cRoom ? "[cut]\n" + older.slice(older.length - cRoom) : older) + close;
  }
  return { title, body, comment, labels: ["player-report"] };
}

const num = (x) => parseInt(x || "0", 10);

/** per address per hour (every report), in a KV namespace (soft limits: KV is eventually consistent) */
export async function ipLimit(kv, ip, now = Date.now()) {
  if (!kv) return null;
  const ipKey = `ip:${ip}:${Math.floor(now / 3_600_000)}`;
  const ipN = num(await kv.get(ipKey));
  if (ipN >= LIMITS.perIpPerHour) return "too many reports from this address; try again in an hour";
  await kv.put(ipKey, String(ipN + 1), { expirationTtl: 7_200 });
  return null;
}

/** for everyone per day: what reaches GitHub */
export async function globalLimit(kv, now = Date.now()) {
  if (!kv) return null;
  const allKey = `all:${Math.floor(now / 86_400_000)}`;
  const allN = num(await kv.get(allKey));
  if (allN >= LIMITS.globalPerDay) return "too many reports today; try again tomorrow";
  await kv.put(allKey, String(allN + 1), { expirationTtl: 172_800 });
  return null;
}

/** both, as before (the address first) */
export async function rateLimit(kv, ip, now = Date.now()) {
  return (await ipLimit(kv, ip, now)) || (await globalLimit(kv, now));
}

/** the device as far as the receiver can tell (its address and model), hashed: no address is kept in a tracker key */
export async function deviceKey(ip, device) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${ip}|${device}`));
  return [...new Uint8Array(bytes)].slice(0, 8).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * MAY THIS REPORT COMMENT ON ITS TRACKER? null = yes; else why not (it is then only counted). Nothing is written here: [noteComment]
 * after the comment is made, [noteCounted] when it is not.
 */
export async function trackerLimit(kv, issue, dev, now = Date.now()) {
  if (!kv) return null;
  const day = Math.floor(now / 86_400_000), hour = Math.floor(now / 3_600_000);
  const [devN, hourN] = await Promise.all([kv.get(`td:${issue}:${dev}:${day}`), kv.get(`th:${issue}:${hour}`)]).then((v) => v.map(num));
  if (devN >= LIMITS.perDevicePerTrackerPerDay) return "this device's report of this today is already on the tracker";
  if (hourN >= LIMITS.perTrackerPerHour) return "the tracker had enough comments this hour";
  return null;
}

export async function noteComment(kv, issue, dev, now = Date.now()) {
  if (!kv) return;
  const day = Math.floor(now / 86_400_000), hour = Math.floor(now / 3_600_000);
  const [devN, hourN] = await Promise.all([kv.get(`td:${issue}:${dev}:${day}`), kv.get(`th:${issue}:${hour}`)]).then((v) => v.map(num));
  await Promise.all([
    kv.put(`td:${issue}:${dev}:${day}`, String(devN + 1), { expirationTtl: 172_800 }),
    kv.put(`th:${issue}:${hour}`, String(hourN + 1), { expirationTtl: 7_200 }),
    kv.put(`tp:${issue}`, "0", { expirationTtl: 2_592_000 }),
  ]);
}

/** a report of a known type counted without a comment (the next comment says how many) */
export async function noteCounted(kv, issue) {
  if (!kv) return;
  await kv.put(`tp:${issue}`, String(num(await kv.get(`tp:${issue}`)) + 1), { expirationTtl: 2_592_000 });
}

const pending = async (kv, issue) => (kv ? num(await kv.get(`tp:${issue}`)) : 0);

async function github(env, path, payload) {
  const res = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "ds-forever-report-worker",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    // GitHub's own reason (e.g. "Resource not accessible by personal access token") into the Worker log and the app's message
    const detail = (await res.text().catch(() => "")).slice(0, 300);
    console.log(`github ${path}: ${res.status} ${detail}`);
    let why = "";
    try { why = JSON.parse(detail).message || ""; } catch {}
    throw new Error(`GitHub answered ${res.status}${why ? `: ${why}` : ""}`);
  }
  return res.json();
}

const json = (status, obj) => new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });

export async function handle(request, env, now = Date.now()) {
  const url = new URL(request.url);
  if (url.pathname !== "/report") return json(404, { error: "not found" });
  if (request.method !== "POST") return json(405, { error: "POST only" });
  if (env.APP_KEY && request.headers.get("X-DSF-Key") !== env.APP_KEY) return json(403, { error: "not DS Forever" });
  if (!env.GITHUB_TOKEN || !env.GITHUB_REPO) return json(503, { error: "the receiver is not set up" });
  const declared = parseInt(request.headers.get("Content-Length") || "0", 10);
  if (declared > LIMITS.maxBytes) return json(413, { error: "the report is too big" });
  const text = await request.text();
  if (text.length > LIMITS.maxBytes) return json(413, { error: "the report is too big" });
  let body;
  try { body = JSON.parse(text); } catch { return json(400, { error: "not JSON" }); }
  const { report, errors } = validate(body);
  if (errors) return json(400, { error: errors.join("; ") });
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const limited = await ipLimit(env.LIMITS, ip, now);
  if (limited) return json(429, { error: limited });
  // a known crash type: a comment on its tracker (or only counted); TRACKERS = "off" in wrangler.toml's [vars] turns this off
  const known = env.TRACKERS === "off" ? null : classify(report);
  if (known) {
    const done = await toTracker(env, report, known, ip, now);
    if (done) return done;               // else the comment could not be made: the report opens its own issue, as before
  }
  const busy = known ? null : await globalLimit(env.LIMITS, now);   // a failed tracker comment was already counted there
  if (busy) return json(429, { error: busy });
  const issue = buildIssue(report);
  try {
    const made = await github(env, "/issues", { title: issue.title, body: issue.body, labels: issue.labels });
    if (issue.comment) await github(env, `/issues/${made.number}/comments`, { body: issue.comment });
    return json(201, { number: made.number, url: made.html_url });
  } catch (e) {
    return json(502, { error: `the issue could not be made (${e.message})` });
  }
}

/**
 * A KNOWN TYPE'S REPORT ON ITS TRACKER: a short comment, or - over a limit - only counted. Either way the app hears the tracker's
 * number, so every app version shows "Report sent: issue #N" (ReportUpload.parse needs a number above 0). null = the comment failed
 * (a locked or deleted tracker, GitHub down): the caller opens an issue instead, so no report is lost.
 */
async function toTracker(env, report, t, ip, now) {
  const kv = env.LIMITS;
  const issueUrl = `https://github.com/${env.GITHUB_REPO}/issues/${t.issue}`;
  const dev = await deviceKey(ip, report.device);
  const why = (await trackerLimit(kv, t.issue, dev, now)) || (await globalLimit(kv, now));
  if (why) {
    await noteCounted(kv, t.issue);
    console.log(`tracker #${t.issue} (${t.id}): counted, ${why}`);
    return json(200, { number: t.issue, url: issueUrl, tracker: t.id, commented: false });
  }
  try {
    const made = await github(env, `/issues/${t.issue}/comments`, { body: buildComment(report, t, safe, await pending(kv, t.issue)) });
    await noteComment(kv, t.issue, dev, now);
    return json(201, { number: t.issue, url: made.html_url || issueUrl, tracker: t.id, commented: true });
  } catch (e) {
    console.log(`tracker #${t.issue} (${t.id}): comment failed (${e.message}); opening an issue instead`);
    return null;
  }
}

export default { fetch: (request, env) => handle(request, env) };
