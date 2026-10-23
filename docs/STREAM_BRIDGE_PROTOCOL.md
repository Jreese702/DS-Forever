# DS Forever bridge protocol, v1

The contract between the Thor app (S2: `BridgeLog`, pairing) and **DS Forever Desktop's bridge** (S3). Video, audio, the controller
and the keyboard are NOT here: they are Moonlight's own protocol to the bundled Sunshine.

**History.** S2 proposed a first draft (2026-10-23 morning: wss on 47900, the code sent in `pair`, raw WCB1 `lines`). S3 had by then
built the bridge (E:/dsf-wt-desktop, `desktop/src/DsfDesktop.Core/Bridge/BridgeServer.cs`, uncommitted when read on 10-23) with a
better pairing (the code never crosses the network; Sunshine's PIN API takes exactly 4 digits, so the bridge makes a separate
Moonlight PIN). **S2 adopted S3's wire format**; this file describes it as the Thor speaks it. S2-only additions are marked
*(Thor extra)*: they are optional for the bridge. Open points are in section 9.

## 1. Finding the PC

| What | Who | mDNS |
|---|---|---|
| Sunshine | bundled Sunshine (already) | `_nvstream._tcp.local.`, port 47989 |
| The bridge | Desktop | `<PC>._dsforever._tcp.local.`, SRV port = the bridge port (default **48210**) |

- `_dsforever._tcp` TXT (Desktop v1): `proto=1`, `id=<Desktop's host id>`, `pc=<PC name>`, `sunshine=<Sunshine's http port>`, `ver`.
  *(Thor extra, optional:)* `tls=1` + `fp=<sha-256 of the bridge certificate, hex>` for a wss bridge; `sid=<Sunshine's uniqueid>`.
- The Thor merges the two adverts by IPv4 address; Desktop's `sunshine` port makes a PC streamable even if its `_nvstream` advert was
  missed. The Thor remembers the paired PC's address, follows it when the same `id` shows up at a new address (DHCP), and tries the
  remembered address when mDNS finds nothing.
- `GET /v1/info` (plain HTTP on the bridge port) answers the same JSON as the welcome's info (the Thor does not need it today).

## 2. Transport

- **WebSocket** (RFC 6455, text frames, no extensions) at `ws://<pc>:<port>/v1/ws`. No `Origin` header from the Thor (the bridge
  accepts only native clients and its own loopback test page).
- One JSON object per text frame, typed by `t`. Unknown types / fields are ignored by both sides.
- Keep-alive: the bridge pings every 15 s; the Thor answers pongs, pings when it has sent nothing for 15 s, and treats 30 s with no
  frame at all as a lost connection.
- *(Thor extra)* the Thor can also speak `wss://` pinned to a certificate fingerprint (trust on first use at pairing) if a later
  Desktop offers it (`tls=1` in the TXT). Not used with Desktop v1.

## 3. Pairing (the code is shown on the PC, typed on the Thor)

1. Thor → `{"t":"pair_hello","proto":1,"device_id":"<id>","name":"AYN Thor","stream":true}`
   (`device_id`: 32 lower-case hex, made once per app install.)
2. Bridge → `{"t":"pair_challenge","nonce":"<hex>","attempts_left":5,"locked":false}` (`locked` = too many wrong codes: Desktop's
   Refresh needed; the Thor says so).
3. Thor → `{"t":"pair_proof","proof":"<hex>"}` with **proof = HMAC-SHA256(key = the 6-digit code, msg = "dsf-pair-v1|" + nonce + "|" +
   device_id)**, lower-case hex. The code itself never crosses the network.
4. Bridge →
   - right: `{"t":"pair_ok","device_id","token":"<43-char base64url>","host_id","pc_name","sunshine":{"http_port","https_port",...},"moonlight_pin":"1234"}`.
     The connection is now an authenticated session (the welcome and the backlog follow on it, section 4). The bridge hands
     `moonlight_pin` to the bundled Sunshine as soon as the Thor's Moonlight pairing request is waiting there.
   - wrong: `{"t":"pair_error","error":"wrong_code"|"code_replaced"|"locked","attempts_left":n}` (the connection stays; the Thor
     closes it and starts over with the next code).
5. Thor runs **Moonlight's pairing handshake** with Sunshine (`/pair` on the http port) with **PIN = `moonlight_pin`** (4 digits).
   While it runs, the Thor keeps reading the session (so the backlog never backs up).
6. Bridge → `{"t":"pair_stream","ok":true}` (or `"ok":false,"error":"..."`) once Sunshine took the PIN.
7. If Moonlight's handshake failed, the Thor sends `{"t":"unpair"}` (Desktop drops the device again: `unpaired`) and says why.
   If Moonlight paired but `pair_stream` never came (15 s), the Thor keeps the pairing (Moonlight's own PAIRED is the proof).
8. The Thor remembers: address, Sunshine's ports and certificate (from the handshake), the bridge port, `token`, `host_id`, `pc_name`.

## 4. A session (every connection after pairing)

1. Thor → `{"t":"hello","proto":1,"device_id":"<id>","token":"<token>"}` (the first frame).
2. Bridge →
   - good: `{"t":"welcome", ...info (host_id, pc_name, sunshine{}, wow{found, flavor, running}, stream, pairing_open)..., "device_id", "backlog":n}`,
     then the backlog's `doc`s, then `{"t":"backlog_end","count":n}`, then live messages.
   - unknown device or token: `{"t":"error","error":"unauthorized","detail":"..."}`, close **4401**. The Thor stops retrying and
     asks to pair again.
3. On every welcome the Thor drops any half-assembled state and sends `{"t":"focus"}` (bring WoW to the front).

## 5. The add-on's documents

- `{"t":"doc","seq":<n>,"gt":<ms>,"doc":"<the JSON document as text>"}` — one document the bridge reassembled from the PC's
  General.log (its port of the Thor's LogTail rules: WCB1 magic, `seq|i/n|` parts, `pad|` dropped, CRLF stripped). `gt` = the game's
  own clock from the line's timestamp prefix, on LogTail's day-of-year scale (-1 = unreadable); the Thor puts it in the document as
  `_gt`, exactly as the local reader does.
- `{"t":"gap","seq":n,"expected":m}` — the add-on refused a line (the Thor's status line says "gap at seq n (expected m)").
- `{"t":"log","event":"restart"|"switch","path","flavor"}` — WoW restarted (it empties General.log at launch) or another WoW log is
  followed; the backlog is emptied. The Thor says "client restarted, resyncing".
- *(Thor extra)* `{"t":"lines","l":["<raw General.log line>", ...]}` — raw WCB1 lines are understood too (the same reassembly as the
  local log), should a bridge ever prefer to send lines.

## 6. Status (bridge → Thor)

- `{"t":"status","text":"..."}` — a line for the companion's status bar.
- `{"t":"stream","state":"started"|"stopped"|"unknown"}` — Sunshine's own state.
- `{"t":"focus_result","ok":bool,"why":"..."}` — the answer to `focus`.
- `{"t":"wow","state":"starting"|"running"|"stopped"|"start_failed","why":"..."}` — Desktop starting WoW on the PC (S3,
  feat/stream-desktop f1c653d6); also `welcome.wow.state` / `.why`, and `focus_result.state` while it starts. The Thor shows
  "Starting WoW on your PC…" over the stream (and while connecting) until `running`, and "WoW didn't start: <why>" for a failure.

## 7. Control (Thor → bridge)

- `{"t":"focus"}` — bring the WoW window to the foreground (Sunshine streams the real desktop).
- `{"t":"ping","id":"..."}` → `{"t":"pong","id":"..."}` (optional; WebSocket pings do the keep-alive).
- `{"t":"unpair"}` → `{"t":"unpaired"}`, then close: the device is removed from Desktop.
- `{"t":"addons_status"}` / `{"t":"addons_install"}` → `{"t":"addons",...}` (Desktop's add-on install; not used by the Thor yet).

## 8. What the Thor tells the player

| The Thor sees | It says |
|---|---|
| No Wi-Fi | "The Thor isn't on Wi-Fi." |
| The remembered PC's /24 isn't this network's, nothing answers, no mDNS | "The PC is on another network." |
| The PC's address refuses Sunshine's and the bridge's ports | "DS Forever Desktop isn't running." |
| Nothing answers (time-outs), no mDNS | "The PC isn't answering: it is off or asleep, or Desktop isn't running." |
| Sunshine answers, the bridge doesn't | "The stream works, but Desktop's bridge isn't answering." |
| Sunshine: not paired / https refuses our certificate; bridge 4401 / `unpaired` | "Pair again: enter the code shown in DS Forever Desktop." |

Every one but "Pair again" retries by itself (1, 2, 4, 8, then every 15 s, ten tries), and at once when the network changes.

## 9. Open points

- **No TLS in v1**: the token crosses the home LAN in clear (the code does not: HMAC). The Thor already speaks pinned `wss://`; when
  Desktop can serve TLS, advertise `tls=1` + `fp` and the Thor uses it with no other change.
- **Sunshine app to stream**: the Thor launches, from Sunshine's applist, the app named "WoW Forever" (Desktop v1's `app_name`),
  else "DS Forever", else "World of Warcraft", else "Desktop", else the first. S3: tell S2 if the name changes.
- `sid` in the TXT would let the Thor tie the adverts together by Sunshine's uniqueid (optional; the address works today).
