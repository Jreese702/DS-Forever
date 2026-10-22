// DS Forever's problem-report receiver (board f-problem-report; the user's pick "In-game upload via my own receiver").
//
// A Cloudflare Worker: POST /report takes one report DS Forever already redacted on the device, checks it, limits how often it is
// called, and opens a GitHub issue in the project's repository with a bot token kept as a Worker SECRET (never in git, never in the
// APK). It answers {"number": N, "url": "..."}.
//
// Abuse: the app's key (APP_KEY) ships inside the APK, so anyone can extract it; it only keeps casual traffic out. The real limits are
// here: a size cap, a strict shape, a per-address and a global rate limit, and every issue carries the "player-report" label.

export const LIMITS = {
  maxBytes: 200_000,          // the whole request
  maxWords: 2_000,            // the player's description
  maxReport: 60_000,          // report.txt
  maxLogs: 150_000,           // logs.txt
  maxDiag: 45_000,            // diagnostics.txt (the app's start-up diagnostics, StartupDiag; optional: older apps send none)
  perIpPerHour: 5,
  globalPerDay: 100,
  issueBody: 65_536,          // GitHub's body limit (characters)
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

/** per address per hour and for everyone per day, in a KV namespace (soft limits: KV is eventually consistent) */
export async function rateLimit(kv, ip, now = Date.now()) {
  if (!kv) return null;
  const hour = Math.floor(now / 3_600_000), day = Math.floor(now / 86_400_000);
  const ipKey = `ip:${ip}:${hour}`, allKey = `all:${day}`;
  const [ipN, allN] = await Promise.all([kv.get(ipKey), kv.get(allKey)]).then((v) => v.map((x) => parseInt(x || "0", 10)));
  if (ipN >= LIMITS.perIpPerHour) return "too many reports from this address; try again in an hour";
  if (allN >= LIMITS.globalPerDay) return "too many reports today; try again tomorrow";
  await Promise.all([
    kv.put(ipKey, String(ipN + 1), { expirationTtl: 7_200 }),
    kv.put(allKey, String(allN + 1), { expirationTtl: 172_800 }),
  ]);
  return null;
}

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
  const limited = await rateLimit(env.LIMITS, ip, now);
  if (limited) return json(429, { error: limited });
  const issue = buildIssue(report);
  try {
    const made = await github(env, "/issues", { title: issue.title, body: issue.body, labels: issue.labels });
    if (issue.comment) await github(env, `/issues/${made.number}/comments`, { body: issue.comment });
    return json(201, { number: made.number, url: made.html_url });
  } catch (e) {
    return json(502, { error: `the issue could not be made (${e.message})` });
  }
}

export default { fetch: (request, env) => handle(request, env) };
