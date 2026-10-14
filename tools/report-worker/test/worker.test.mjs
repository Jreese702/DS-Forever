// node --test (no packages): the receiver's checks, limits and issue, with GitHub and KV faked
import test from "node:test";
import assert from "node:assert/strict";
import { validate, scrub, safe, buildIssue, rateLimit, handle, LIMITS } from "../src/worker.js";

const good = () => ({
  version: "1.0.7 (107)", addons: "set 125", device: "AYN Thor, Android 13", words: "bars went blank after a fight",
  report: "DS Forever problem report\nRecorded in detail: yes", logs: "diag  10-14 12:00 doc: home fight=1", recorded: true,
});

class FakeKV {
  constructor() { this.m = new Map(); }
  async get(k) { return this.m.get(k) ?? null; }
  async put(k, v) { this.m.set(k, v); }
}

function env(extra = {}) {
  return { GITHUB_TOKEN: "t", GITHUB_REPO: "Jreese702/DS-Forever", APP_KEY: "k", LIMITS: new FakeKV(), ...extra };
}

function req(body, headers = {}) {
  const text = typeof body === "string" ? body : JSON.stringify(body);
  return new Request("https://r.example/report", {
    method: "POST", body: text,
    headers: { "Content-Type": "application/json", "X-DSF-Key": "k", "CF-Connecting-IP": "203.0.113.9", ...headers },
  });
}

function fakeGitHub(calls) {
  globalThis.fetch = async (url, init) => {
    calls.push({ url, body: JSON.parse(init.body), auth: init.headers.Authorization });
    if (url.endsWith("/issues")) return new Response(JSON.stringify({ number: 42, html_url: "https://github.com/Jreese702/DS-Forever/issues/42" }), { status: 201 });
    return new Response("{}", { status: 201 });
  };
}

test("a good report passes; the shape is strict", () => {
  assert.ok(validate(good()).report);
  for (const [k, v] of [["version", "abc"], ["words", "  "], ["words", "?!"], ["device", 3], ["report", ""]]) {
    const b = { ...good(), [k]: v };
    assert.ok(validate(b).errors, `${k}=${v}`);
  }
  assert.ok(validate([]).errors);
  assert.ok(validate({ ...good(), words: "x".repeat(LIMITS.maxWords + 1) }).errors);
  assert.ok(validate({ ...good(), logs: "x".repeat(LIMITS.maxLogs + 1) }).errors);
  for (const v of ["1.0.7", "1.0.7 (107)", "1.0.7-test4", "1.0.7-test4 (107)"]) assert.ok(validate({ ...good(), version: v }).report, v);
});

test("the second privacy pass and safe markdown", () => {
  assert.equal(scrub("a@b.co Jaina#11234 10.0.0.1"), "<email> <battletag> <ip>");
  const s = safe("```html\n@someone hi");
  assert.ok(!s.includes("```"));
  assert.ok(!/@someone/.test(s));
});

test("the issue: title, label, collapsed blocks, within GitHub's limit", () => {
  const i = buildIssue(validate(good()).report);
  assert.equal(i.title, "Player report: bars went blank after a fight");
  assert.deepEqual(i.labels, ["player-report"]);
  assert.ok(i.body.includes("<details><summary>report.txt</summary>"));
  assert.ok(i.body.includes("<details><summary>logs.txt</summary>"));
  assert.equal(i.comment, null);
  const big = buildIssue(validate({ ...good(), logs: "line\n".repeat(29_000) }).report);
  assert.ok(big.body.length <= LIMITS.issueBody, `body ${big.body.length}`);
  assert.ok(big.comment && big.comment.length <= LIMITS.issueBody, `comment ${big.comment?.length}`);
  assert.ok(big.body.includes("[older lines in the first comment]"));
});

test("rate limits: 5 an hour per address, 100 a day for all", async () => {
  const kv = new FakeKV();
  const now = Date.UTC(2026, 9, 14, 12);
  for (let i = 0; i < 5; i++) assert.equal(await rateLimit(kv, "1.2.3.4", now), null);
  assert.match(await rateLimit(kv, "1.2.3.4", now), /this address/);
  assert.equal(await rateLimit(kv, "1.2.3.4", now + 3_600_000), null);        // the next hour
  const kv2 = new FakeKV();
  for (let i = 0; i < 100; i++) assert.equal(await rateLimit(kv2, `ip${i}`, now), null);
  assert.match(await rateLimit(kv2, "ip-new", now), /today/);
});

test("POST /report makes the issue and answers its number", async () => {
  const calls = [];
  fakeGitHub(calls);
  const res = await handle(req(good()), env());
  assert.equal(res.status, 201);
  assert.deepEqual(await res.json(), { number: 42, url: "https://github.com/Jreese702/DS-Forever/issues/42" });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].auth, "Bearer t");
  assert.ok(calls[0].url.endsWith("/repos/Jreese702/DS-Forever/issues"));
});

test("the receiver refuses what it should", async () => {
  fakeGitHub([]);
  assert.equal((await handle(new Request("https://r.example/other", { method: "POST", body: "{}" }), env())).status, 404);
  assert.equal((await handle(new Request("https://r.example/report"), env())).status, 405);
  assert.equal((await handle(req(good(), { "X-DSF-Key": "wrong" }), env())).status, 403);
  assert.equal((await handle(req(good()), env({ GITHUB_TOKEN: "" }))).status, 503);
  assert.equal((await handle(req("{not json"), env())).status, 400);
  assert.equal((await handle(req({ ...good(), version: "x" }), env())).status, 400);
  assert.equal((await handle(req("x".repeat(LIMITS.maxBytes + 1)), env())).status, 413);
  const e = env();
  for (let i = 0; i < 5; i++) await handle(req(good()), e);
  assert.equal((await handle(req(good()), e)).status, 429);
});

test("a GitHub failure says so", async () => {
  globalThis.fetch = async () => new Response("{}", { status: 401 });
  const res = await handle(req(good()), env());
  assert.equal(res.status, 502);
  assert.match((await res.json()).error, /401/);
});
