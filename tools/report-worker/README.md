# DS Forever's problem-report receiver

A small Cloudflare Worker that turns DS Forever's in-game "Send report" into a GitHub issue, so players never leave the game.

- The app sends the report it already **redacted on the device** (no logins, BattleTags, names or chat). The receiver scrubs it once more (e-mails, BattleTags, IP addresses).
- The issue's title carries the app version, and its body has a collapsed **Diagnostics** section: the app's start-up diagnostics (device, game folder, art, start-up stages, its own log; optional `diag`, 45 000 characters at most).
- `POST /report` checks the size (200 KB at most), the shape and the version. It limits each address to 5 reports an hour and everyone to 100 a day, and either comments on a known crash type's tracker issue (below) or opens an issue labelled `player-report` in `Jreese702/DS-Forever`, and answers `{"number": N, "url": "..."}`.
- The GitHub token is a **Worker secret**. It is never in git and never in the APK.
- `APP_KEY` (optional) ships inside the APK, so anyone can extract it. It only keeps casual traffic out. The real protection is the limits above, plus the label, which lets you filter or bulk-close the issues.

## Known crash types go to their tracker issue

Most automatic crash reports are the same crash again. A report whose signature is a known type (`src/trackers.js`) opens no new issue. Instead it adds a short comment to that type's tracker issue:

| Signature | Tracker | Matched by |
|---|---|---|
| `low-memory-kill` | #56 | the automatic report "Android closed DS Forever because the device ran low on memory" |
| `anr` | #153 | the automatic report "DS Forever stopped responding" |
| `libcasc-native-crash` | #472 | the automatic report "DS Forever crashed (native code)" whose newest crash record's crashing thread is in `libcasc.so` |
| `blz51914400-sign-in` | #237 | a short typed report (300 characters at most, not recorded in detail) naming BLZ51914400 |
| `captcha-authenticator-disconnect` | #165 | a short typed report about a captcha or authenticator that ends in a disconnect, an error or no way in |

- Every other report (an unknown type, a long description, a report recorded in detail) opens its own issue as before.
- The comment holds the version, add-on set, device model, RAM tier (8 / 12 / 16 GB, from the diagnostics), the signature, the player's words and up to 12 key lines (the memory exits, the ANR's main thread, or the native crash's frames). It never includes logs.txt, report.txt or the full diagnostics, and it goes through the same scrub as an issue.
- Limits: one comment per device per tracker per day (the device is a hash of its address and model; no address is stored in these keys), and 4 comments per tracker per hour. Over a limit the report is only counted, and the next comment says how many were counted. Counted reports do not use up the 100-a-day budget. The 5-an-hour limit per address still applies to every report.
- The app is told the tracker's number either way, so every app version shows "Report sent: issue #56". If the comment fails (for example, the tracker is locked or deleted), the report opens its own issue, so nothing is lost.
- To change a tracker, edit `TRACKERS` in `src/trackers.js` and deploy. To turn the routing off without a code change, set `TRACKERS = "off"` under `[vars]` in `wrangler.toml` and deploy.

## Setup (once, by the project owner)

You need a Cloudflare account (the free tier is enough) and Node.js 18 or later.

1. **Create the Cloudflare account** at https://dash.cloudflare.com/sign-up, then sign in from this folder:
   ```
   cd tools/report-worker
   npm install
   npx wrangler login
   ```
2. **Create the GitHub bot token.** Use the option that matches who owns the token.
   - **A separate bot account (recommended).** Invite it to `Jreese702/DS-Forever` as a collaborator with the **Triage** role, so it can create issues and apply labels, but cannot push code. A fine-grained token can only cover repositories its own account owns, so on the bot account create a **classic** token: Settings > Developer settings > Personal access tokens (classic) > Generate new token, with only the **`public_repo`** scope. The bot is a collaborator on this repository alone, so in practice that is all the token reaches.
   - **Your own account.** Settings > Developer settings > Fine-grained personal access tokens > Generate new token. Set Resource owner to Jreese702 and Repository access to **Only select repositories** > `DS-Forever`. Under Permissions choose **Issues: Read and write** and leave everything else at No access.
   - Either way, set an expiry, note the date, and repeat steps 2 and 4 before it expires.
   - Create the label `player-report` in the repository once: Issues > Labels > New label. The token applies the label but cannot create it.
3. **Create the rate-limit store**, then paste the id it prints into `wrangler.toml` (`[[kv_namespaces]] id = ...`):
   ```
   npx wrangler kv namespace create LIMITS
   ```
4. **Give the Worker the token.** Paste it when asked; it is stored encrypted at Cloudflare:
   ```
   npx wrangler secret put GITHUB_TOKEN
   ```
5. *(Optional)* Set a casual-traffic key. Pick any random string, put it in `wrangler.toml` under `[vars]` as `APP_KEY = "..."`, and use the same string for the app's `dsfReportKey` (step 7).
6. **Deploy.** It prints the address, for example `https://ds-forever-reports.<you>.workers.dev`:
   ```
   npx wrangler deploy
   ```
7. **Point the app at it.** Build with the endpoint (and the key, if you set one). Add these to `gradle.properties` or the build command:
   ```
   dsfReportEndpoint=https://ds-forever-reports.<you>.workers.dev/report
   dsfReportKey=<the APP_KEY, or leave it out>
   ```
   Without `dsfReportEndpoint` the app keeps saving the report and opening the pre-filled issue in the browser.
8. **Try it.** In the game: Settings > Problem report > Send. Type a few words, then Send report. The Home notice should go "Compiling…", then "Sending…", then "Report sent: issue #N", and the issue appears with the `player-report` label.

## Day to day

- Watch the reports: Issues filtered by `label:player-report`.
- Too many reports: lower `perIpPerHour` / `globalPerDay` in `src/worker.js` and run `npx wrangler deploy` again.
- Stop the receiver at once: `npx wrangler delete`. The app then shows "Couldn't send", saves the report, and offers the pre-filled issue instead.
- Rotate the token: create a new one (step 2), then run `npx wrangler secret put GITHUB_TOKEN` again.

## Tests

```
node --test
```

The tests fake GitHub and the store; nothing goes over the network.
