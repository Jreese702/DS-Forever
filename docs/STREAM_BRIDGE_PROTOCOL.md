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
- `GET /v1/info` (plain HTTP on the bridge port) answers the same JSON as the welcome's info. *(issue #414)* When the stored bridge
  port answers nowhere and mDNS is silent, the Thor asks `/v1/info` on Desktop's other ports (48210-48219) and uses one only when
  its `host_id` is the paired Desktop's; a `_dsforever` advert of the paired Desktop (its `id`) on another port is followed too.

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

### 3a. The PC's address inside the code (Desktop 0.2.37; the user's pick 2026-11-18)

A Thor that can't see the `_dsforever` advert (another subnet, a guest / mesh Wi-Fi that drops multicast, Tailscale / WireGuard)
pairs by typing the code alone: Desktop's code is the 6-digit secret followed by the PC's address, digits only (the Thor's keypad).
Desktop shows it as `482 913 · 100 2790` (the secret, then the address part in threes; a last four instead of a lone digit).

    code  = secret (6 digits, as above) + tail
    tail  = "" (no address known, or an older Desktop) | entry+ check
    entry = 1 + 5 digits: 192.168.0.0/16 (the offset in the range) | 2 + 8: 10.0.0.0/8 | 3 + 7: 172.16.0.0/12
          | 4 + 7: 100.64.0.0/10 (Tailscale) | 5 + 10: any IPv4 | 6 + 5: the bridge port when it is not 48210 (at most once)
          | 0, 7, 8, 9: reserved (the Thor refuses the code)
    check = Damm's check digit over the tail's entries (never over the secret)

Desktop carries at most two addresses: the home network's IPv4 on the interface with a default gateway (not a Hyper-V / WSL switch),
then the first Tailscale IPv4. Examples: LAN 192.168.1.23 = `482 913 · 100 2790` (13 digits); with Tailscale 100.71.133.93 too =
`482 913 · 100 279 404 928 933` (21). Vectors: `PairAddressTests.cs` (Desktop) and `PairCodeTest.kt` (Thor).

The Thor: a code with an address pairs with the PC mDNS found at one of its addresses; else it asks `GET /v1/info` at each address
(at once, 2.5 s) and pairs with the first Desktop answer exactly as with an advert (its `fp` must then match the wss certificate);
else with the PC picked on the screen; else it says "PC not reachable at <addresses>". A typo in the address part is refused before
anything is dialled (the check digit catches every single wrong digit and every swap of neighbours).

**Trust is unchanged.** The address is only where to dial, as an mDNS answer is (unauthenticated in both cases). The proof is keyed by
the 6 secret digits alone (step 3), so the code's strength and the bridge's attempt limits are what they were; the address digits are
public facts about the PC and add nothing to guess. A program at a wrong or spoofed address is in the place a spoofed advert is
today, with the same checks: its certificate must be the one its `/v1/info` names; another Desktop there answers `wrong_code`. What
it can get is the Thor's HMAC over its own nonce, which, as today, a 6-digit secret can't protect from an offline search (why the
code is single-use, lives 10 minutes and shows each new device in Desktop's list; a PAKE would close it). Over Tailscale the address
is better than an advert: Tailscale itself binds 100.x to the PC's node key. After pairing, every route is pinned (the bridge's
certificate, Sunshine's certificate), so a later impostor at that address fails the pin.
Compatibility: an older Thor types the first 6 digits (Desktop's hint says so) and pairs by mDNS as before; a new Thor with an older
Desktop gets a 6-digit code and pairs as before. The Thor remembers the address it paired at (`Route.after`), and the welcome's
`lan` / `tailscale` lists fill in the other routes, so reconnects need no mDNS; `AddressPolicy` still moves to the home LAN when mDNS
sees the PC there.

## 4. A session (every connection after pairing)

1. Thor → `{"t":"hello","proto":1,"device_id":"<id>","token":"<token>"}` (the first frame).
2. Bridge →
   - good: `{"t":"welcome", ...info (host_id, pc_name, sunshine{}, wow{found, flavor, running}, stream, pairing_open)..., "device_id", "backlog":n}`,
     then the backlog's `doc`s, then `{"t":"backlog_end","count":n}`, then live messages.
   - unknown device or token: `{"t":"error","error":"unauthorized","detail":"..."}`, close **4401**. The Thor stops retrying and
     asks to pair again.
3. On every welcome the Thor drops any half-assembled state and sends `{"t":"focus"}` (bring WoW to the front).
4. *(issue #414)* The welcome (and `/v1/info`) carries `"gt_now"`: the PC's clock when it was sent, on the documents' `gt` scale
   (local time, like WoW's log). The Thor shifts every `gt` of the connection by (its own clock - `gt_now`), so a PC in another time
   zone (or with a drifted clock) still gives "current" documents. Without it (an older Desktop) the Thor takes the gap of the
   documents after `backlog_end`, rounded to whole quarter hours (within 2 min), once that gap has held (within 20 s) for 5 s of the
   Thor's clock, and otherwise shifts nothing: an older Desktop can send its old log's documents after `backlog_end` (read after
   the bridge was up), and those never hold still.

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
- *(issues #304 / #357)* `wow.input` (welcome, `/v1/info`, the `wow` message, `focus_result`): why the Thor's taps can't reach
  WoW. `elevated` = WoW runs above Desktop's integrity level (as administrator): Windows drops Sunshine's mouse and keys on it (UIPI),
  the virtual pad still works. `not_filling` = WoW's client area doesn't fill the streamed monitor: the companion's taps (shares of
  the whole stream) miss. `""` = fine, or not known. `wow.input_why` is the Thor's line for it. A new word holds 4 s before it is
  said; on a change, and right after the welcome while one is set, a `status` carries the line ("taps reach WoW again" when it
  clears). Desktop's window shows the same as a notice. Read-only on the PC (the window's client rectangle, the token's level).

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
