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
- **[S3] Sunshine's port is never 47989.** Desktop picks the first free base port from 52989, 53989, 54989, 55989, 56989 and keeps
  it (0.2.31: the stock block 47984-48010 is left to a separately installed Sunshine or Apollo; a saved 47989 from 0.2.30 or older
  moves to 52989 once, and the Thor follows the welcome's port). The Thor must use `sunshine` (TXT) / `sunshine.http_port` (pair_ok, welcome) for Moonlight, and tie the right
  `_nvstream` advert by `sid` when several share the PC's address.
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

### 3a. Other network: the PC's address as a second code (Desktop 0.2.37; the user's picks 2026-11-18, design B "Two steps")

Everyone types the 6-digit secret exactly as before. A Thor that can't see the `_dsforever` advert (another subnet, a guest / mesh
Wi-Fi that drops multicast, Tailscale / WireGuard) also types the **network code** Desktop shows under it, quieter, as "Other network"
(a globe, then smaller boxes). The Thor's pairing panel has the same words and globe: an "Other network" button beside the keypad
(touch; the controller reaches it with D-pad down past the boxes, A presses it), and Pair with no PC found opens the row by itself,
the cursor in it, with "PC not found" under it. Both sides draw the codes as boxes, groups of 3.

    net   = entry+ check                                        (digits only; always whole groups of 3)
    entry = 1 + 5 digits: 192.168.0.0/16 (the offset in the range) | 2 + 8: 10.0.0.0/8 | 3 + 7: 172.16.0.0/12
          | 4 + 7: 100.64.0.0/10 (Tailscale) | 5 + 10: any IPv4 | 6 + 5: the bridge port when it is not 48210 (at most once, first)
          | 7 + 5 + 7: 192.168.0.0/16 then 100.64.0.0/10 (home, then Tailscale: the common pair)
          | 0, 8, 9: reserved (the Thor refuses the code)
    check = 1 to 3 digits bringing the code to whole groups of 3; the last is always Damm's check digit over everything before it:
            1 = Damm | 2 = Damm of the entries read backwards, then Damm | 3 = ISO 7064 MOD 97-10's two, then Damm
            none for a lone "1" entry: the commonest code stays 6 digits

The entries are 6 digits or more and the check 3 at most, so the code's length says where the entries stop. Desktop carries at most two
addresses: the home network's IPv4 on the interface with a default gateway (not a Hyper-V / WSL switch), then the first Tailscale IPv4.
Examples: LAN 192.168.1.23 = `100 279` (6 digits, no check); with Tailscale 100.71.133.93 too = `700 279 049 289 390` (15).
Vectors: `PairAddressTests.cs` (Desktop) and `PairCodeTest.kt` (Thor). Typos: wherever there is a check, Damm over the whole code
catches every single wrong digit and every swap of neighbours, even a wrong kind digit that moves where the entries end. The 6-digit
home code (the user's call: two groups, no separate check digit) catches only a wrong kind digit or an offset past 65535; another
home address simply doesn't answer as this PC (below).

The Thor joins the two (secret + network digits) as one code internally: a code with an address pairs with the PC mDNS found at one of
its addresses; else it asks `GET /v1/info` at each address (at once, 2.5 s) and pairs with the first Desktop answer exactly as with an
advert (its `fp` must then match the wss certificate); else with the PC picked on the screen; else it says "PC not reachable at
<addresses>". A typo is refused before anything is dialled ("Check the Other network code."). A wrong secret clears the six boxes and
keeps the network digits.

**Trust is unchanged.** The address is only where to dial, as an mDNS answer is (unauthenticated in both cases). The proof is keyed by
the 6 secret digits alone (step 3), so the code's strength and the bridge's attempt limits are what they were; the network digits are
public facts about the PC and add nothing to guess. A program at a wrong or spoofed address is in the place a spoofed advert is
today, with the same checks: its certificate must be the one its `/v1/info` names; another Desktop there answers `wrong_code`. What
it can get is the Thor's HMAC over its own nonce, which, as today, a 6-digit secret can't protect from an offline search (why the
code is single-use, lives 10 minutes and shows each new device in Desktop's list; a PAKE would close it). Over Tailscale the address
is better than an advert: Tailscale itself binds 100.x to the PC's node key. After pairing, every route is pinned (the bridge's
certificate, Sunshine's certificate), so a later impostor at that address fails the pin.
Compatibility: an older Thor app types the 6 digits and pairs by mDNS as before (it has no Other network row); a new Thor with an
older Desktop gets a 6-digit code and pairs as before. The Thor remembers the address it paired at (`Route.after`), and the welcome's
`lan` / `tailscale` lists fill in the other routes, so reconnects need no mDNS; `AddressPolicy` still moves to the home LAN when mDNS
sees the PC there.

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
- `{"t":"sunshine","sunshine":{...}}` *(Desktop 0.2.32)* — the welcome's `sunshine` object again, sent when Desktop moved its
  Sunshine to a new base port (Windows reserved one of its ports, or another program holds one), and whenever its `wait` changes.
  The Thor dials the new ports.
- `sunshine.wait` *(Desktop 0.2.32, in the welcome and every `sunshine` / `host_check_result`)* — what a stream's start waits for:
  `"screen"` = Desktop is making (or would make, for this Thor) its own virtual screen, each make a Sunshine restart; `"starting"`
  = its Sunshine is starting (it probes its encoders before it binds any port, about 26 s) or a heal is restarting it; `""` = go.
  The Thor holds its stream start while it is set, 90 s at most, and its curtain says "PC making its stream screen" / "PC
  streaming host starting". An older Desktop never sends it (go).
- `{"t":"host_check_result","state":"ok"|"restarting"|"failed"|"streaming","sunshine":{...}}` *(Desktop 0.2.32)* — the answer to
  `host_check`; after `restarting` a second one follows with `ok` (every port open again) or `failed` (3 tries spent; the last
  try moves the base when none has yet). `streaming` = a stream session is up (our sunshine holds its UDP ports), not just
  Sunshine's BUSY (an app launched). An `ok` found at once is the only answer (no second message). Nothing is judged before the
  restarted Sunshine has started (its ports bound, or a "Couldn't bind" / "Configuration UI available" line, or 90 s), so the
  answer can take up to about 105 s; a Thor that gave up still takes a later `ok` as one more try.
- `{"t":"pc_test_result","steps":[{"id","name","state":"pass"|"fail"|"skip"|"not_run","line","fix"}],"screen":"own"|"real"|"none","screen_line":"...","screen_hz":N,"pad":"vigem"|"missing"|"unknown"}`
  *(Desktop 0.2.32)* — the answer to `pc_test`: the PC's checks in order (ports, rtsp, encoder, screen, controller), stopped at
  the first failure; `screen` = what a stream gets, `screen_hz` its top refresh (0 = unknown). No names; IPs cut to a.b.x.x.
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
- **[0.2.1] Signed in, not just running (the user, 2026-10-28).** The `wow` message, the welcome's / `/v1/info`'s `wow` and a
  `focus_result` also carry: `screen` = `none` | `starting` | `login` | `signing_in` | `charselect` | `world` (from the game's
  connections, its General.log's sign-in lines and the add-on's documents), `focused` (WoW is the foreground window), `bnet` =
  `unknown` | `not_running` | `starting` | `signin_needed` | `signed_in` (Battle.net's "Battle.net Login" window = signed out; its
  titles only, never its files), `needs_you` (bool), `login_error` (the game's own reason, e.g. `308`). A `wow` message is sent
  whenever any of them changes. On a connect (stream start or `focus`) Desktop: starts Battle.net plainly if it isn't running; if it is
  signed out, brings its sign-in window to the front (the player signs in through the stream; Desktop never sees, stores or types
  credentials); then no WoW = start it; WoW at the login screen or showing a sign-in error = close it (WM_CLOSE, ended only if
  still at the login screen after 15 s) and start a fresh one; signed in = focus it. One automatic retry per connect: a second client
  that does not sign in gives `needs_you` true and a `status` "Sign-in needs you on the PC's Battle.net", and no new attempt for 2
  minutes. The Thor keeps its loading curtain over the stream until `screen` is `charselect` / `world` and `focused` (an older
  Desktop without `screen`: `focused` alone), and lifts it only for Battle.net's sign-in while `bnet` is `signin_needed`.
- **[0.2.33] Battle.net's sign-in on the Thor again (the user, 2026-11-17: "we had it clipping the Battlenet screen before").**
  0.2.8 to 0.2.32 sent `bnet` = `unknown` always and never started Battle.net, so the Thor's clipped sign-in (its `show` rectangle,
  the zoom, the saved login typed after `bnet_ready`) could not happen. From 0.2.33 `bnet` is the PC's real state again, except
  that `signin_needed` is never sent while WoW is at character select or in the world. On a connect, before WoW starts (and at WoW's
  login screen): Battle.net not running = started (WoW waits up to 90 s for it, then starts without it); signed out = its sign-in
  window in front on the streamed monitor, with `show`, until signed in; then WoW starts plainly (no `-launcherlogin`). WoW is not
  pulled to the front over Battle.net's sign-in. `needs_you`'s `status` is now "Press Play in Battle.net".
- **[0.2.33] A 309 is one neutral `connect` = `failed`.** 0.2.31 and 0.2.32 sent `region` = `refused` for WoW's "Fatal login
  error (309)" after its region (super district) screen. The game's log does not say whether the district was refused or its realm
  was out of reach: its `subRegionCount` is Battle.net's sub-region count (1 on the user's PC 2026-11-17 22:57 and 22:58, though the
  region screen offered districts and `superDistrictID` 2 was asked for), and the district list logs no count. So from 0.2.33 every
  309 is `login_error` `309` plus `connect` = `failed` (the Thor: "WoW can't reach its servers"; a newer Thor reads an older
  Desktop's `region` = `refused` the same way), and `region` is never sent. After a 309 Desktop starts WoW again for a connected
  Thor without using its one retry, at most `RegionRetryMax` (5) times per connect: WoW closed itself = started again; WoW stayed at
  its own login screen (not the region screen) with its `login.txt` used up = restarted, so the new run asks the Thor
  (`login_again`) for the login. With the PC's Battle.net signed out, its sign-in comes up on the Thor first. Whether a signed-out
  PC Battle.net causes the 309 is not known (WoW's own Glue logon had succeeded before it).
- **[0.2.33] A disabled SudoVDA.** Apollo's SudoVDA adapter present but disabled in Windows reads as there, not missing: no install
  is offered; the `status` line (and, headless, `sunshine.problem` `screen_disabled` with its `why`) is "Enable SudoMaker display
  adapter in Device Manager". Desktop never enables a device. The one-time install question waits until no Thor is connected or
  streaming.
- **[0.2.34] Stop and show it (the user's ruling 2026-11-18; 0.2.33 restarted WoW every ~20 s while Blizzard said "no servers
  available").** The 0.2.33 restarts after a 309 are gone (WoW closed itself = started again; WoW back at its login after a
  `login.txt` run = restarted, `RegionRetryMax`). After a server error the supervisor stops; the `wow` message keeps `connect` =
  `failed`, adds `connect_code` (WoW's own code, `309`: `login_error` goes with the next screen switch) and `stopped` = `true` while
  WoW is not signed in. The Thor shows the error in red ("WoW can't reach its servers (309)") with "Retry (A)" (A or a tap; nothing
  else of the pad reaches the PC meanwhile) and sends **`{"t":"wow_retry"}`**: Desktop clears the error, asks Blizzard's version
  again, then starts WoW, or restarts it at its login screen, ONCE per press (the new run asks the Thor for the login again,
  `login_again`, as before; with the PC's Battle.net signed out its sign-in comes first). An older Thor app ignores `stopped`
  and shows the 0.2.33 line over WoW's screen; an older Desktop ignores `wow_retry` (it never says `stopped`).
- **[0.2.34] An outdated PC game.** Before WoW is started (and every tick while a Thor is connected) Desktop compares the active
  `wowf` row of `<WoW>/.build.info` (its `Version`; its `Branch` = the region) with Blizzard's patch server
  (`http://<region>.patch.battle.net:1119/wowf/versions`, `VersionsName` of that region; cached 5 minutes, 1 minute after no answer,
  read in the background). Older = WoW is NOT started; the `wow` message says `pc_game` = `outdated`, `game_installed`,
  `game_current` (full versions) and `stopped` = `true`; the Thor: "PC game needs an update (70938 -> 70959)" with "Retry (A)",
  which checks again (`wow_retry`). No answer (offline) never blocks a start; a first check under way holds the start a few
  seconds at most. Desktop patching the game itself (from Blizzard's CDN, no Battle.net) comes later.
- **[0.2.35] One start per press; an older Thor.** A `wow_retry` while the last one's restart or start is still under way is that
  same one (dropped); the press's own client closing again without a server error is "Press Play in Battle.net", never a second
  start. A Retry not answered yet goes when the Thor disconnects or a new connect begins. The Thor says **`"wow_stop": true`** in
  `hello` / `pair_hello` when it shows the stop with its own Retry. An older Thor (none; Thor 1487) reads neither `stopped` nor
  `pc_game`, so while Desktop is stopped with WoW not running the `wow` message also says `state` = `start_failed` ("WoW didn't
  start", not "Starting WoW" for ever) and the reason as `why`; WoW at its login keeps `running`. That older Thor's reconnect counts
  as its Retry while Desktop is stopped, at most once every 2 minutes. Blizzard's version is asked only while a Thor is connected
  (or a stream wants WoW). The start timeout's `why` is "no WoW window after 180 s" (never "sign in on the PC").
- **[0.2.35] A missing install record.** `_forever_\Wow.exe` there but no `wowf` row in `.build.info` (the Agent dropped it on the
  user's PC, 2026-11-18 06:32), or a row of another build than Wow.exe's own version (FileVersionInfo's text, read only) = WoW is NOT
  started: `pc_game` = `repair`, `game_installed` = Wow.exe's version, `stopped` = `true`; the Thor: "PC game needs a repair" with
  "Retry (A)". Desktop keeps its own copy of the last good row (`<data>\patch\wowf_row.json`, saved when the row's Version is
  Wow.exe's); whenever it reads the install (every 10 s while a Thor is connected, and again right before every start), a MISSING row is added back from that copy when it is for Wow.exe's build (the file's other lines
  byte for byte, its line ending kept, `.build.info.dsf-bak` beside it). A row that is there is never replaced. Never `.product.db`,
  never the CASC store.
- **[0.2.36] Desktop patches the PC game itself.** An outdated game (or one missing its install record) is patched from Blizzard's
  CDN with no Battle.net, BY ITSELF as soon as Desktop sees it while a Thor is connected: one automatic attempt per Thor connect,
  then one attempt per `wow_retry`. The method is the Thor's own updater's (the build's install manifest picked by the row's tags,
  each changed `_forever_` file checked by its MD5); it writes only the configs in `Data/config`, missing `Data/indices/*.index`, the
  changed `_forever_` files (Utils first, `Wow.exe` last; a file of the same content key in another flavor folder is COPIED) and the
  `wowf` row of `.build.info` (last, read again right before). Never `Data/data`, never `.product.db`. It waits (up to 5 minutes)
  while the Battle.net Agent is at work on the folder, never patches under a running WoW, and keeps ONE backup of the replaced
  files (`_forever_\.dsf-backup\<old build>`) until the first login on the new build reaches character select, then deletes it; a
  first start that crashes puts the old files back. A Desktop killed mid-install undoes it at its next start (its journal,
  `<data>\patch\journal.json`). The `wow` message carries **`pc_patch`**: `{"state": checking | waiting | downloading | installing
  | loading | failed | cancelled, "from": "70938", "to": "70959", "done": bytes, "total": bytes, "file", "file_no", "files",
  "code", "need_mb", "eta_s"}` (`waiting` + `code` `agent` = Blizzard's updater is busy; `loading` = done, the new client starts;
  `failed` codes: `no_answer`, `missing`, `checksum`, `space` (+ `need_mb`), `busy` (WoW running or a file in use), `agent_busy`,
  `no_write`, `format`, `new_failed`, `error`). The Thor shows a bar ("Updating the game 70938 -> 70959", "64 / 127 MB") with
  "Cancel (B)", which sends **`{"t":"pc_patch_cancel"}`** (refused once installing began: it is short), and a failure as a red row
  with "Retry (A)" (`wow_retry`). An older Thor (no bar) gets the patch's line as `why`. The patch over, WoW is started once.
  While it runs (and the new client loads) `state` is `starting`, never `start_failed` (1487 put "sign in on the PC" next to a
  `start_failed`); a failed or cancelled patch stays a stop. A WoW open at its login is CLOSED by the attempt (never one signed
  in); none starts by itself while WoW is signed in. The Agent is asked again right before the install. Any put-back follows what
  is on disk (a half-done `File.Replace`, a crash right after a swap); one that can't finish keeps its journal for the next try.
  A patch while an install still waits for its first login folds into it (the one backup kept). A failed or cancelled `pc_patch`
  goes once the game is current by another route.
- **[0.2.37] The virtual screen, quiet by default (the user's ruling 2026-11-18).** "Stream on its own screen" is OFF by default
  (a 0.2.32-0.2.36 settings file's "on" was that version's default and reads as off; the player's own pick from 0.2.37 on is kept).
  A PC with a monitor on and the setting off streams that monitor with no question, no pop-up and nothing about drivers to the
  Thor; no driver is even looked for. A headless PC (or the setting on) gets the setup in Desktop's own window: SudoVDA turned off
  = "Turn on" (pnputil /enable-device with the adapter's instance id found by its hardware id, through Windows' admin prompt, on
  the player's click only), no driver = the Virtual Display Driver's install, a driver ready = a one-time test of our screen, "Start
  Desktop when I sign in" (the HKCU Run entry, off by default) and a note that a headless PC must sign in to Windows by itself.
  The one-time install question box is gone. To the Thor, every headless screen problem goes as `sunshine.problem` `needs_screen`
  with `why` "pc needs a virtual screen: set it up in Desktop" (never `screen_disabled` / `no_screen` any more; a Thor reads all
  three as that one line); the `status` line carries only a screen being made ("Making the stream's own screen", "Virtual screen
  ready"), never a driver's absence.
- **[S3] The streamed monitor.** Sunshine captures one monitor (the primary unless told otherwise). When no stream is running and WoW
  is on another monitor, Desktop points Sunshine at WoW's monitor (Sunshine restarts, ~25 s, and a `status` says so); during a stream
  it moves the WoW window onto the streamed monitor instead.
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
- `{"t":"stream_mode","w":1920,"h":1080,"fps":60}` *(Desktop 0.2.31)* — the Thor's stream mode, sent after each welcome. On a PC with
  no screen on, a virtual screen Desktop makes through an installed SudoVDA takes this mode. No answer. *(0.2.32, "Stream on its
  own screen", default on)* Every PC: while a Thor is connected or streaming, Desktop makes OUR virtual screen at this mode
  (SudoVDA if installed; else the Virtual Display Driver's monitor, attached at the nearest mode it offers; else, after the one-time
  install question, the real screen with the `status` "Streaming your screen: no virtual screen driver"), points its Sunshine's
  `output_name` at it and moves WoW onto it; 60 s after the last Thor/stream it is removed and WoW goes back to its monitor. Never
  made or removed mid-stream on a PC with a real screen. Apollo's screens and a VDD monitor the player keeps on are never touched.
  `status` lines: "Making the stream's own screen", "Virtual screen ready", "Streaming your screen: virtual screen failed".
- `{"t":"host_check"}` *(Desktop 0.2.32)* — the Thor's RTSP timed out twice while HTTP answered: Desktop checks its Sunshine's whole
  port set (TCP base-5 / base / base+21 listening by our sunshine.exe, RTSP answering a connect, the UDP base+9..11 free and outside
  Windows' excluded ranges, no "Couldn't bind" in its log) and, when no stream is live, restarts it or moves it to a new base. Never
  touches another Sunshine or Apollo. Answer: `host_check_result`.
- `{"t":"pc_test"}` *(Desktop 0.2.32)* — the Thor's Test connection: Desktop runs the same checks as its window's Test button
  (our Sunshine's ports listening and free, a TCP connect to RTSP, a video encoder in its log since its start, our own virtual screen
  made and removed again when none is there and no stream is live, else the real screen, and ViGEmBus). Logged to desktop.log and
  last_test.txt. Answer: `pc_test_result`.
- `{"t":"screen_retry"}` *(Desktop 0.2.32)* — the player pressed Retry on the Thor's problem screen: Desktop's own virtual screen
  starts its tries over. It gives up after 3 failed makes (or screens its Sunshine didn't list) in a Thor session - a real screen is
  streamed, or a headless PC says "PC needs a virtual screen" (`problem` "needs_screen") - and tries again only when the Thor
  reconnects, on this message, or on a Test. No answer.
- `{"t":"addons_status"}` / `{"t":"addons_install"}` → `{"t":"addons",...}` (Desktop's add-on install; not used by the Thor yet).
- **The Thor's add-ons on the PC** *(Desktop 0.2.40; the user 2026-11-18: "My desktop seems to be missing the required addons for the
  app like polestar and questie", "questie especially", "part of the DS forever startup should be maintaining/updating enabled addons")*.
  - Thor → `{"t":"addons_want","v":1,"acct":"<SvSync's account hash>","questiedb":{"want":true},"managed":[{"id","key","title","tag",
    "asset","bytes","sha256","folders":[...],"url","license","want"}],"imported":[{"folder","version","enabled","big","allowed","bytes",
    "files":[{"p","n","h"}]}],"enabled":{"<folder>":true|false}}` after every welcome (WoW running or not; built on its own thread).
    `managed` = every ThirdPartyAddons package with its pin and whether the Thor wants it now; `imported` (only down a pinned wss
    connection) = Settings > Add-ons > Import's add-ons with each file's path, size and sha256; `enabled` = on / off in the Thor's
    AddOns.txt (on anywhere = on; off in every file naming it = off; not named = left out).
  - Desktop (PcAddons): QuestieDB from Questie/QuestieDB's latest release (GitHub digest / SHA256SUMS; every 6 h; reinstalled when
    deleted), then each managed pin ONLY from its own source (`polestar` / `spoken`: that repo's release by the pinned tag, the asset's
    size as pinned; `manners` / `questtrail`: `https://mediafilez.forgecdn.net/files/.../<asset>`; any other id or host refused), size +
    sha256 checked, resumed, licence files into every folder; `want` false = Desktop's own copy to `Interface/AddOns-backup-dsforever`.
    Then each enabled imported add-on, a part at a time: Desktop → `{"t":"addon_file_get","id","folder","p","off","len"}` (len ≤ 256 KB),
    Thor → `{"t":"addon_file","id","folder","p","off","data":"<base64>"}` or `{"t":"addon_file_err","id","why"}` (only a folder on its
    Imported list, only a plain path inside it, ≤ 512 KB). Each file's sha256 checked; a broken copy resumes. Then `enabled` into the
    PC's AddOns.txt (the account by `acct`, else every account), ONLY for folders Desktop put in.
  - Never: a folder Desktop didn't put in (the player's own, or one of the same name: "yours"), anything while WoW runs on the PC
    (deferred to the next run: Desktop's start, the next connect, right before Desktop starts WoW - which waits up to 90 s only while a
    wanted add-on is missing entirely). Staged in `Interface/AddOns-staging-dsforever`, moved in folder by folder, put back on a failure.
  - Desktop → `{"t":"addons_pc","state":"working","line","done","total"}` (at most once a second) and `{"t":"addons_pc","state":"done",
    "line","ok","items":[{"name","state":"ok"|"installed"|"updated"|"removed"|"yours"|"skipped"|"waiting"|"deferred"|"refused"|"failed",
    "why"}]}`. The Thor's status line shows progress, and when done only what is not in (QuestieDB first).
- **Settings follow** *(Desktop 0.2.37; the user's pick 2026-11-18; build 1489 ruling: ours only, WoW's own sync CVars are left to the game - on by default on both installs)* — DS Forever's OWN two saved tables
  (WowCompanionDB, WowCompanionSpellsDB) between the Thor's kept copy (SvKeep) and the PC, only down a pinned wss connection, only while
  WoW is not running on the PC. Merging is the Thor's alone (SvSync / SvMerge: per entry, the Thor's local entries win the first sync and
  every conflict, nothing is ever dropped - the user's hard rule "dont delete my current hunters settings on local"); Desktop only reads
  and writes the two files (WowSvSync).
  - Thor → `{"t":"sv_read","v":1,"acct":"<sha-256 hex of the Thor's account folder name, lower-cased; never the name>"}`
    after a welcome with WoW not running. Desktop picks the account folder (the hash's, else the remembered game account's "!" pick
    from login_file, else the only one; else none) and answers
    `{"t":"sv_files","state":"ok"|"none"|"running","why","acct","files":[{"var","exists","text","hash":"<sha-256 of the file>","at"}]}`.
  - Thor → `{"t":"sv_write","v":1,"acct","docs":[{"var","n","lua":"{...}","if_hash"}]}` (sent after every `ok`, `docs`
    possibly empty). Desktop writes `
<var> = <lua>
` only if the file's hash is still `if_hash` ("" = still missing), `n` > 0 and
    the text is a bare table, a dated backup first (SavedVariables/dsf-sync-backups, the newest 5), temp + replace; it answers
    `{"t":"sv_written","results":[{"var","state":"written"|"same"|"changed"|"refused"|"running"|"none"|"failed"}]}`. `written` /
    `same` = the PC holds the table: the Thor's base for its later three-way merges of the PC's live `svkeep` documents.
  - A start waits up to 6 s for a `sv_write` still owed.
- `{"t":"end_session","quit_asked":true|false}` *(Desktop 0.2.39, feat/pause-power; the user 2026-11-18: "When streaming this
  should also close wow on the PC and revert everything back and be the clean way to disconnect")* — the Thor's clean disconnect (its
  pause menu's power button, its waiting screen's Exit game). Answered `{"t":"session_ending"}` at once, then
  `{"t":"session_ended","wow":"closed"|"none"|"left","put_back":bool,"why"}`. Desktop lets the supervisor go (WoW is not started
  again), closes ONLY the `_forever_` client (path match; never retail or an unreadable one): with `quit_asked` (the Thor tapped the
  game's own Exit Game over the stream) its exit is waited for up to 30 s, then its window's close (WM_CLOSE, as its X) up to 20 s,
  ended only if it stays (10 s); its login.txt goes; the controller mode and WoW's window keys go back at once (WowConfigKeep, no
  30 s grace, still never while a WoW runs and only once Config.wtf has settled); the monitor's mode is looked at as soon as the
  stream has gone (DisplayKeep, no 20 s grace). Desktop stays running. The Thor stops the picture right after sending (after a
  0.6 s settle for its Exit Game tap), waits for `session_ended` (5 s for `session_ending`: an older Desktop ignores the word; 90 s
  at most), disconnects the bridge, then closes DS Forever (the power button) or goes back to the chooser (Exit game).

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
