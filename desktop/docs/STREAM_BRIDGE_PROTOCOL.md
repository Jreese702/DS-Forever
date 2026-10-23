# DS Forever bridge protocol, v1

The contract between the Thor app (S2: `BridgeLog`, pairing) and **DS Forever Desktop's bridge** (S3). Video, audio, the controller
and the keyboard are NOT here: they are Moonlight's own protocol to the bundled Sunshine.

**This copy** is S3's (E:/dsf-wt-desktop/desktop/docs/), taken from S2's (E:/dsf-wt-stream/docs/) on 2026-10-23 and changed only where
Desktop now does more; every change is marked **[S3]** and listed in section 10 so S2 can pull them. The wire format is unchanged.

**History.** S2 proposed a first draft (2026-10-23 morning: wss on 47900, the code sent in `pair`, raw WCB1 `lines`). S3 had by then
built the bridge (E:/dsf-wt-desktop, `desktop/src/DsfDesktop.Core/Bridge/BridgeServer.cs`, uncommitted when read on 10-23) with a
better pairing (the code never crosses the network; Sunshine's PIN API takes exactly 4 digits, so the bridge makes a separate
Moonlight PIN). **S2 adopted S3's wire format**; this file describes it as the Thor speaks it. S2-only additions are marked
*(Thor extra)*: they are optional for the bridge. Open points are in section 9.

## 1. Finding the PC

| What | Who | mDNS |
|---|---|---|
| Sunshine | bundled Sunshine (already) | `_nvstream._tcp.local.`, port 47989 **or another base port [S3]: see below** |
| The bridge | Desktop | `<PC>._dsforever._tcp.local.`, SRV port = the bridge port (default **48210**) |

- `_dsforever._tcp` TXT (Desktop v1): `proto=1`, `id=<Desktop's host id, 32 lower-case hex>`, `pc=<PC name>`,
  `sunshine=<Sunshine's http port>`, `ver`. **[S3] Desktop also sends** `tls=1` + `fp=<sha-256 of the bridge certificate (DER), 64
  lower-case hex>` (the same port speaks wss, section 2) and `sid=<Sunshine's uniqueid>` once Sunshine is up.
- **[S3] Sunshine's port is not always 47989.** Desktop picks the first free base port from 47989, 52989, 53989, 54989, 55989, 56989
  (another Sunshine or Apollo may hold 47989: the development PC runs Apollo on 47989 / 48989 / 49989, so Desktop uses **52989**
  there) and keeps it. The Thor must use `sunshine` (TXT) / `sunshine.http_port` (pair_ok, welcome) for Moonlight, and tie the right
  `_nvstream` advert by `sid` when several share the PC's address.
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
- **[S3] wss on the same port:** Desktop v1 serves TLS too (`wss://<pc>:<port>/v1/ws`, `https://.../v1/info`), with a self-signed
  RSA-2048 certificate made once and kept (`fp` in the TXT, also in `pair_ok` and `/v1/info`). A connection is TLS when its first byte
  is a ClientHello; plain `ws://` keeps working. The Thor's pinned wss (trust on first use at pairing) can be switched on now.

## 3. Pairing (the code is shown on the PC, typed on the Thor)

1. Thor → `{"t":"pair_hello","proto":1,"device_id":"<id>","name":"AYN Thor","stream":true}`
   (`device_id`: 32 lower-case hex, made once per app install.)
2. Bridge → `{"t":"pair_challenge","nonce":"<hex>","attempts_left":5,"locked":false}` (`locked` = too many wrong codes: Desktop's
   Refresh needed; the Thor says so).
3. Thor → `{"t":"pair_proof","proof":"<hex>"}` with **proof = HMAC-SHA256(key = the 6-digit code, msg = "dsf-pair-v1|" + nonce + "|" +
   device_id)**, lower-case hex. The code itself never crosses the network.
4. Bridge →
   - right: `{"t":"pair_ok","device_id","token":"<43-char base64url>","host_id","pc_name","sunshine":{"http_port","https_port",...},"moonlight_pin":"1234"}`.
     **[S3]** plus `"fp"` when the bridge serves TLS.
     The connection is now an authenticated session (the welcome and the backlog follow on it, section 4). The bridge hands
     `moonlight_pin` to the bundled Sunshine as soon as the Thor's Moonlight pairing request is waiting there.
   - wrong: `{"t":"pair_error","error":"wrong_code"|"code_replaced"|"locked","attempts_left":n}` (the connection stays; the Thor
     closes it and starts over with the next code).
5. Thor runs **Moonlight's pairing handshake** with Sunshine (`/pair` on the http port) with **PIN = `moonlight_pin`** (4 digits).
   While it runs, the Thor keeps reading the session (so the backlog never backs up).
6. Bridge → `{"t":"pair_stream","ok":true}` (or `"ok":false,"error":"..."`) once Sunshine took the PIN. **[S3]** The bridge waits up
   to 90 s for the Thor's request to reach Sunshine; with a wrong PIN Sunshine refuses within ~10 s and `pair_stream` says
   `"the stream pairing was refused (the PIN didn't match)"`.
7. If Moonlight's handshake failed, the Thor sends `{"t":"unpair"}` (Desktop drops the device again: `unpaired`) and says why.
   If Moonlight paired but `pair_stream` never came (15 s), the Thor keeps the pairing (Moonlight's own PAIRED is the proof).
8. The Thor remembers: address, Sunshine's ports and certificate (from the handshake), the bridge port, `token`, `host_id`, `pc_name`.

## 4. A session (every connection after pairing)

1. Thor → `{"t":"hello","proto":1,"device_id":"<id>","token":"<token>"}` (the first frame).
2. Bridge →
   - good: `{"t":"welcome", ...info (host_id, pc_name, sunshine{}, wow{found, flavor, running}, stream, pairing_open)..., "device_id", "backlog":n}`,
     then the backlog's `doc`s, then `{"t":"backlog_end","count":n}`, then live messages.
   - unknown device or token: `{"t":"error","error":"unauthorized","detail":"..."}`, close **4401**. The Thor stops retrying and
     asks to pair again. **[S3]** A device removed in Desktop gets the same 4401 (detail "this device was removed ..."); if it is
     connected at that moment it first gets `{"t":"unpaired"}`.
   - **[S3]** one session per device: a second `hello` from the same `device_id` replaces the first, which is closed with **4409**
     (the Thor should not reconnect on 4409 while its newer connection is up).
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
- **[S3] Starting WoW.** Desktop starts WoW Forever when a stream starts (Sunshine reports a client streaming) or the Thor sends
  `focus`, and WoW isn't running, unless the user turned off "Start WoW when streaming" (default on). It uses Battle.net's own launch
  (`Battle.net.exe --exec="launch_uid <product>"`, product `wow_classic_beta` until 2026-11-04, `wowf` from then, as the APK's
  WowProduct picks), or the game exe when Battle.net isn't installed; it never touches credentials. When the window appears Desktop
  brings it to the front (and onto the streamed monitor).
  - `{"t":"wow","state":"starting"|"running"|"stopped"|"start_failed","why":"..."}` on every change, plus a `status` text
    ("Starting WoW on your PC…", "WoW didn't start: <why>", "WoW is running on your PC").
  - `welcome` / `/v1/info`: `wow.state` (same words) and `wow.why`, beside `wow.running`.
  - `focus_result` while WoW starts: `{"ok":false,"state":"starting","why":"starting WoW"}`; the Thor shows "Starting WoW on your
    PC…" and needs no second `focus` (Desktop focuses WoW itself when its window appears).
  - `start_failed` after 3 minutes without a WoW window usually means Battle.net is waiting for the player to sign in on the PC.
- **[S3] The streamed monitor.** Sunshine captures one monitor (the primary unless told otherwise). When no stream is running and WoW
  is on another monitor, Desktop points Sunshine at WoW's monitor (Sunshine restarts, ~25 s, and a `status` says so); during a stream
  it moves the WoW window onto the streamed monitor instead.

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
  Desktop can serve TLS, advertise `tls=1` + `fp` and the Thor uses it with no other change. **[S3] Done:** Desktop serves it and advertises `tls=1` + `fp`.
- **Sunshine app to stream**: the Thor launches, from Sunshine's applist, the app named "WoW Forever" (Desktop v1's `app_name`),
  else "DS Forever", else "World of Warcraft", else "Desktop", else the first. S3: tell S2 if the name changes.
- `sid` in the TXT would let the Thor tie the adverts together by Sunshine's uniqueid (optional; the address works today). **[S3] Done:** `sid` is sent; it matters on PCs with more than one Sunshine/Apollo (section 1).

## 10. S3's notes (Desktop facts the Thor can rely on)

- **Sunshine's PIN rules (measured on the bundled Sunshine 2026.914.233613):** `/api/pin` takes **exactly 4 digits** ("PIN must contain
  exactly 4 numeric digits") and, in this version, a `pairing_id` (GET `/api/pin` lists the Moonlight requests waiting). Desktop
  handles both; the Thor only needs `moonlight_pin`. Pairing by PIN with no web page is proven end to end on the PC by Desktop's
  tests (a Moonlight-protocol client, the real Sunshine, the bridge's PIN).
- **Sunshine's app list:** one app, **"WoW Forever"** (the desktop itself, no command; its prep commands tell Desktop a stream started,
  which is when Desktop brings WoW to the front). Unchanged name for v1.
- **Host-side encoder settings:** Sunshine probes NVENC, then AMF, then Quick Sync itself (the dev PC: AMF) and advertises HEVC / AV1
  when the GPU can encode them. Resolution, fps and bitrate are the client's request: **1920x1080, 60 fps, HEVC, ~20 Mbps** are the
  Thor's defaults to send.
- **Backlog:** the documents since the current log began, capped at 8 MiB of messages (50 000 documents), as a fresh LogTail would read.
- **Keep-alive:** the bridge pings every 15 s and closes a session with no frame for 60 s.
- **Test vector** for the proof: HMAC-SHA256(key "123456", "dsf-pair-v1|00ff|thor-1") =
  `1728773c570e30c5f0032df3751d125403088e2848828f1b49d2a68c808f50f9`.
- **Changes in this copy vs S2's (for S2 to pull):** the `[S3]` lines in sections 1, 2, 3 and 4, and this section.
