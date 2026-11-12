# PARTY MODE: THE BUILD CONTRACT (step 1)

Branch `feat/party-mode`, worktree `E:/dsf-wt-party`, from main 96907313 (v1.0.12). Lead's contract, 2026-10-28. Two workers build
against it in parallel: the ADD-ON worker and the APP worker. Nothing here is implemented yet.

Authority, in order: the user's rulings in `C:/Users/Jrees/wow-companion-design/party_mode/SPEC_DRAFT.md` (later sections win: v10, v13,
v17, v18), then `FEASIBILITY.md` ("DESIGN CHANGES FORCED"), then this file. A conflict between this file and a ruling is a bug in this
file: stop and ask the lead.

Rules that bind both workers:
- Offline only. No adb, emulator, Thor or network. Commit on `feat/party-mode` only, author `DS Forever <noreply@example.invalid>`,
  message ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push.
- No trace (feedback_wow_no_trace): no CreateMacro / EditMacro, no SetBinding / SaveBindings, no SetCVar, no bar writes. Keys only as
  `SetOverrideBindingClick` on OUR owner frames. Data only in `WowCompanionSpellsDB`.
- No Blizzard window open / close / menu code from add-on code (project_wow_taint_open_close). Plain pad handlers call engine
  functions only.
- No new add-on file and no .toc edit (reference_wow_new_toc_file). New add-on code goes at the END of an existing file, inside its
  own `local function x() ... end x()` block, because PadMap's main chunk is at Lua's 200 locals (PadMap.lua:698).
- Everything a character owns is keyed by name-realm (feedback_wow_per_character), as `whoKey()` (PadMap.lua:132-135).
- UI text: labels and icons, at most one short line under a title (feedback_wow_ui_terse_text). The cursor is `UiKit.focus` only
  (UiKit.kt:1678; feedback_wow_cursor_look).
- No key is ever SENT (the L1 key channel stays off, TapBridge.kt:37). The app talks to the add-on only by taps on the pad.
- "A / B / X / Y" in this file are the GAME's names: PAD1 = A, PAD2 = B, PAD3 = X, PAD4 = Y (router positions 8 / 7 / 5 / 6,
  Interact.lua:5350-5352). The Thor reports its printed letter, so the user's A is PAD1.

---------------------------------------------------------------------------------------------------------------------------------

## 1. THE DOCUMENTS (add-on -> app)

All go through `WC.send(WC.encode(...))` (Sink.lua:56). "Flush" = `WC.push()` (Sink.lua:179, coalesced per frame). "Throttled" =
`WC.pushThrottled()` (Sink.lua:158). All are sent by PadMap.lua (Spells), version string `v = PV` (`"m77"`), except `pmenu` (Quests).

### 1.1 Delivery rules (an app restart must lose nothing)

General.log is emptied after the app reads it and LogTail replays only what is still there, so a document sent only "on change" is gone
after an app restart. Therefore:
1. `party` and `partycfg` RIDE THE CORE HEARTBEAT: `hooksecurefunc(WC, "snapshot", fn)` re-sends both on reasons `heartbeat`, `login`,
   `zone`, `manual` (the Chat.lua:589-604 pattern; Core's 15 s ticker, Core.lua:158). No extra flush: they land in that snapshot's push.
2. RESEND on demand: the app taps party row col 22 (section 2). The add-on answers with `partycfg`, `party` and a `partybox`
   (`k = "state"`), flushed.
3. On change: as each document says below.
The app re-asks (col 22) when: the first CURRENT document after its start arrives and no current `party` came yet; Home is shown again
(another room, or the app back from the background); Party mode is turned on and `partycfg` is older than this game session. At most
once per 10 s. Only while `GameInput.live()` (GameInput.kt:65; TapBridge refuses otherwise, TapBridge.kt:190-197).

"Current" (CompanionHost.kt:1549, `GameInput.note`): replayed `party` / `partycfg` may be SHOWN; nothing is ever TAPPED because of a
replayed document; `partybox` and `partyack` count only when current.

### 1.2 `party`: the roster and Party mode's state

Sent: on change of any field, coalesced 0.5 s, then throttled; a roster change (GROUP_ROSTER_UPDATE: someone joins, leaves, units
renumber) is FLUSHED at once and bumps `rs`. Also when Party mode turns on / off. Sent solo too (one member, yourself). Example:

```json
{"type":"party","v":"m77","rs":12,"p":{
  "on":true,"aside":null,"box":2,"fight":false,"grp":true,"sel":"bound",
  "m":[{"u":"player","i":0,"n":"Korvash","c":"SHAMAN","col":"ff0070dd","role":"HEALER","dead":false,"off":false,
        "pt":"MANA","hpMax":412,"pmMax":380,"hp":100,"full":true},
       {"u":"party1","i":1,"n":"Brannoc","c":"WARRIOR","col":"ffc69b6d","role":"TANK","dead":false,"off":false,
        "pt":"RAGE","hpMax":510,"hp":72,"full":false,"rng":true}]}}
```

| field | meaning |
|---|---|
| `rs` | roster sequence; +1 on every roster change. The app refuses cell taps for 0.5 s after `rs` changes (a unit may have renumbered) |
| `on` | Party mode on (the PartyPad's `wc-party`, read in the PostClick / out of combat) |
| `aside` | null = the router routes; else why it stands aside (`"room"`, a game window's name): Party mode is SUSPENDED (no press reaches it) |
| `box` | 1-based index into `m` of the boxed member; present only while `on` |
| `fight` | PadMap's `fighting()` (PadMap.lua:145-146) |
| `grp` | `IsInGroup()` |
| `sel` | Select: `"bound"` (Select toggles Party mode now), `"dialog"` (left to the game: an unfocused invite waits), `"aside"`, `"off"` (in a fight, not carried) |
| `m[]` | DISPLAY ORDER = BOX ORDER: you first, then party1..party4 that exist (the game's order). Max 5. Party only (raids later) |
| `u` / `i` | unit token / unit index (0 = player, 1..4 = partyN). `i` is the TAP column offset (section 2) |
| `n` | `UnitName(u)` (plain), else `"?"` |
| `c`, `col` | `UnitClass` token; `RAID_CLASS_COLORS[c].colorStr` (as Map.lua:3159-3162 does) |
| `role` | `UnitGroupRolesAssigned(u)`: `TANK` / `HEALER` / `DAMAGER` / `NONE` |
| `dead`, `off` | `UnitIsDeadOrGhost`, `not UnitIsConnected` |
| `pt` | power TOKEN (second return of `UnitPowerType`): `MANA` / `RAGE` / `ENERGY` / `FOCUS` / other. Type only: no amount is ever sent (mana is secret) |
| `hpMax`, `pmMax` | `UnitHealthMax`, `UnitPowerMax` when plain (WC.isPlain), else absent |
| `hp` | the health ESTIMATE, integer percent 0..100 (always an estimate: the app always prints "~") |
| `full` | true when the estimate is anchored full (the bar has no soft edge) |
| `rng` | optional: `C_Spell.IsSpellInRange(<A's spell>, u)` ONLY if plain; absent otherwise. Not drawn in v1 |

The estimate (add-on, pure, between `-- PARTY-EST-BEGIN` / `-- PARTY-EST-END`, tested): per member GUID: a new member or a /reload =
full; `UNIT_MAXHEALTH` -> max; `UNIT_COMBAT(u, "WOUND"|"HEAL", ..., amount)` with a PLAIN amount moves it (clamped 0..max); out of combat,
alive, no `UNIT_HEALTH` pulse for 6 s -> full (anchor); dead -> 0; alive again after dead -> 50 %. Your own cell: `WC.vitals()`
(PadMap.lua:4108) when it has a value, else the same model. A tick changes `hp` only when it moves >= 1 %.

### 1.3 `partycfg`: what every button / touch does, the choosers, Smart Y's party list, the pad columns

Sent: on any setting change (FLUSHED), SPELLS_CHANGED, Smart Y on / off, login; heartbeat ride; resend.

```json
{"type":"partycfg","v":"m77","c":{
  "slots":{"a":{"id":331,"n":"Healing Wave","i":136052,"k":true,"d":true},
           "x":{"id":8004,"n":"Lesser Healing Wave","i":136043,"k":false,"d":true},
           "zla":{"id":526,"n":"Cure Poison","i":136067,"k":false,"d":true},
           "zlx":{"id":2870,"n":"Cure Disease","i":136083,"k":false,"d":true},
           "zly":null,"zra":{"id":2008,"n":"Ancestral Spirit","i":136077,"k":false,"d":true,"res":true},"zrx":null,"zry":null},
  "yc":null,"touch":null,
  "spells":[{"id":331,"n":"Healing Wave","i":136052}],
  "sy":{"on":false,"list":[{"id":131,"n":"Water Breathing","i":136148,"k":false}],"known":[],
        "pick":{"party1":{"id":131,"n":"Water Breathing","i":136148}}},
  "pad":{"row":"party","tgt":0,"tsp":5,"on":10,"off":11,"focus":12,"menu":17,"resend":22,"pbsel":23,
         "pbops":{"remove":31,"up":32,"down":33,"add":34},"mdone":35,
         "ptab":{"row":"padtab","tab":26,"slot0":27,"none":37}}}}
```

| field | meaning |
|---|---|
| `slots.<s>` | `s` in `a, x, zla, zlx, zly, zra, zrx, zry`. null = Empty. `k` = learned now (else drawn dim, never cast). `d` = the class default (never chosen). `res` = a resurrection spell |
| `yc` | Y's IN COMBAT action: null = Inspect (the player menu), else a spell object |
| `touch` | what a touch on a cell does: null = Target (the game's real target), else a spell object |
| `spells` | the CHOOSER list: this character's known spells castable on a party member, book order (section 3.6) |
| `sy.on` | Smart Y on (`OV.smartOn()`, PadMap.lua:3634) |
| `sy.list` | Smart Y's PARTY buff list in priority order (max 8; single buffs, no "or" rows in v1) |
| `sy.known` | party buffs this character knows (the Add chooser) |
| `sy.pick` | per unit token: the buff Y would cast now (out of combat only; absent = all up = Inspect). Empty object in a fight |
| `pad` | the column map below (the app uses these, never constants of its own; a missing key = that action unavailable) |

### 1.4 `partybox`: every press Party mode takes, and every on / off

Sent from the PostClick of WowCompanionPartyPad and of the three flip buttons (section 3.2), on the press (down) only, FLUSHED, in and
out of fights (a plain insecure post-hook reading attributes, as facecast does, PadMap.lua:1276-1283).

```json
{"type":"partybox","v":"m77","b":{"n":57,"on":true,"box":2,"cnt":3,"u":"party1","k":"cast","key":"PAD1","ly":"","sp":"Healing Wave",
  "fight":true,"pm":false}}
```

`n` = press counter for this session (the app drops a repeat `n`). `k`: `move` (D-pad), `cast`, `none` (an Empty place / both triggers),
`menu` (Y opened the player menu for `u`), `key` (a press while the menu sub-state is on: `key` says which), `on`, `off`, `state` (the
resend's answer). `ly` = `""` / `"zl"` / `"zr"`. `pm` = the menu sub-state is on. The app's box follows the newest `n`.

### 1.5 `partyack`

`{"type":"partyack","v":"m77","op":"slot"|"yc"|"touch"|"pb"|"resend","ok":true,"why":null,"slot":"a","id":331}`. FLUSHED. `why` in a
fight is `"combat"` (settings never change in a fight: secure attributes are locked) and pulses Home's red flash as PadMap's `ack`
does (PadMap.lua:516-522).

### 1.6 `pmenu` (Quests q49): the player menu for a party unit

Today's document (Quests.lua:2212) gains `unit`, `which` (`"SELF"` / `"PARTY"`), `nm` (the member's name) and `src` (`"party"` when
read for Party mode, absent for the map module's portrait). Items may carry `scr: "party"` with `sc` = a column of the PARTY row
(Set Focus on that unit), or today's `sc` on `pfocus`; and `ap: "whisper"` (the app opens the Chat room to reply to `nm`; Quests sets
`WC.chatLastTell = nm` first, the target Chat's typed Reply uses, Chat.lua:1388). The app routes by `src` (CompanionHost.kt:1623 today
sends every pmenu to the map).

### 1.7 What stays as it is

`padmap` (PadMap.lua:507-514), `facecast`, `cappad` and the rest are unchanged. `padtabctl` gains party fields (section 4.4).

---------------------------------------------------------------------------------------------------------------------------------

## 2. THE TAP CHANNEL

### 2.1 The pad upgrade: pad11 (TapPad.lua, Quests' file; the ADD-ON worker edits it on the lead's word)

The pad is full: 54 of `ROWS_MAX` 54 with every bag and the reagent bag (Radial.lua r16 note; count: WowCompanion 22 incl. 6 bag + 6
bmv rows, Calendar 1, Character 2, GroupFinder 4, Hub 2, Interact 6, Professions 3, Quests 3, Social 2, Spells 10). Party mode needs
ONE new secure row. The upgrade is FEASIBILITY's "pad10"-style raise, named **pad11** (`pad10` / `pad10c` are taken by the key channel):
- `PADV = "pad11"` (TapPad.lua:83), `ROWS_MAX = 56` (TapPad.lua:85). Nothing else changes: same cells (6 x 2 game px), same 40 columns,
  same geometry, same `fits()` rule (TapPad.lua:214-219), same `describe()` (TapPad.lua:656-672).
- Why it is safe: a row is PLACED and DESCRIBED only while it ends 1 px above the pop-up dialogs (`fits`). On the Thor's own UI scale
  `popupTop` is 126.6 game px, so rows 0..55 fit (row 55 ends at 12 + 56 x 2 = 124). At the SMALLEST UI scale (`popupTop` 121.5) rows
  54-55 are not placed and not described, exactly as pad8 / pad9 drop rows: the app then finds those cells missing and shows their
  actions unavailable (TapBridge.cellCentre returns null, TapBridge.kt:119-128). No TapBridge change.
- Row numbers follow registration order (TapPad.lua:250-257). The party row registers at Spells' LOAD (file scope), so it is numbered
  before the bag rows Bags registers at runtime; on a small UI scale the casualties are the last runtime bag rows, not Party mode.
- One real tap test on the device is owed before release (probe P11, section 6.3).

### 2.2 The new row: `"party"`, SECURE, 40 columns, `opts.onPress = true`

Registered by PadMap with `WC.pad.secureRow("party", 40, init, onClick, { onPress = true })` (TapPad.lua:341-354). Cells WITH a secure
action act on the tap's RELEASE (the template's rule); cells with `type = nil` run `onClick` on the PRESS (pad6b, TapPad.lua:294-306).
Attributes are set by `init` out of combat only; a change in a fight waits for PLAYER_REGEN_ENABLED (TapPad.lua:312-314). `u` below =
0 player, 1..4 party1..party4.

**SUPERSEDED for cols 0-9 (PadMap m129 / m130, 2026-11-11):** cols 0-4 are the PICK cells: each cell carries the PARTYPICK pre
snippet itself (`SecureHandlerWrapScript` on the cell, no `type` at rest): Party mode off = `type="target"`; the picked member = the
Touch action (`type="target"` or `type="spell"`); another member = the pick moves (`wc-box` + `wc-u` on the pad), nothing cast. The
first edge of a press decides and stores it (`wc-pdec`), later edges re-use it, the release clears it; one partybox a press
(`wc-pseq`). m129's hidden `WowCompanionPartyPick1..5` relay buttons are gone (review: a relayed `Click(button)` could miss the
delegate's action edge). Cols 5-9 are spare. The rows below are the old layout, kept for the history.

| col | name | cell | what it does |
|---|---|---|---|
| 0-4 | TGT u | `type="target"`, `unit=U[u]` (static) | Touch = Target: the member becomes the GAME's target (on purpose). Works in fights |
| 5-9 | TSP u | `type="spell"`, `spell=<touch spell>`, `unit=U[u]`; `type=nil` while Touch = Target | Touch = a spell: cast on that member at once |
| 10 | ON | `type="click"`, `clickbutton=WowCompanionPartyOn` | Party mode ON (absolute; a repeat is harmless). In fights too (the Interact `idlg` -> RoomEnter pattern) |
| 11 | OFF | `type="click"`, `clickbutton=WowCompanionPartyOff` | Party mode OFF (absolute) |
| 12-16 | FOCUS u | `type="focus"`, `unit=U[u]` | the player menu's Set Focus (FocusUnit is protected). Clear Focus stays Quests' `pfocus` col 1 |
| 17-21 | MENU u | no action (press) | read the player menu for U[u]: `WC.pmenu.read(U[u], "party")` -> `pmenu` doc |
| 22 | RESEND | no action (press) | send `partycfg` + `party` + `partybox{k="state"}`, flushed |
| 23-30 | PBSEL k | no action (press) | select Smart Y party-buff row k = col - 22 (1..8); fresh 2 s (the `sbOp` rule, PadMap.lua:3816-3820) |
| 31 / 32 / 33 | PB remove / up / down | no action (press) | on the freshly selected row; answered `partyack op "pb"` |
| 34 | PB add | no action (press) | a fresh "add" pick; the spell follows as Book's two-tap number in the SAME batch (`WC.smartYWants` / `WC.smartYSpell`, PadMap.lua:3832-3862, op `"padd"`) |
| 35 | MENU DONE | `type="click"`, `clickbutton=WowCompanionPartyMenuDone` | the app's player menu closed: the PartyPad leaves its menu sub-state (secure, so it works in fights) |
| 36-39 | spare | `type=nil` | refused with `partyack ok=false why="unknown column"` |

### 2.3 The Controller room's Party tab by touch: `padtab` columns 26-37 (Book.lua's plain row, Spells)

`padtab` uses 0-25 today (Book.lua:1316-1336, 1338-1362); 26-39 are free. New codes:

| col | what |
|---|---|
| 26 | show the Party mode tab (5th tab; `touchTab(5)`) |
| 27-36 | pick party slot k = col - 26 (a no-action pick, fresh 2 s; also moves the tab's cursor): 1 `a`, 2 `x`, 3 `yc`, 4 `touch`, 5 `zla`, 6 `zlx`, 7 `zly`, 8 `zra`, 9 `zrx`, 10 `zry` |
| 37 | ONE action on the freshly picked slot: its no-spell value (Empty; Inspect for `yc`; Target for `touch`) |
| 38-39 | refused, as today's unused columns |

A SPELL for the picked slot = Book's existing two taps (`spells` row `vb<g>` then `vo<k>`, Book.lua:1190-1210) in the same batch, after
the slot pick: Book routes a spell to `WC.padTabCtl.touchSpell(id)` while the Controller tab is open (Book.lua:1229-1233); PadTabCtl's
party view turns it into the slot's setting (section 4.4). A spell not in `partycfg.spells` is refused.

### 2.4 How the app taps (TapBridge, unchanged)

`TapBridge.taps(ctx, pad, cells)` (TapBridge.kt:167) for one action = one batch (a pick then its action, all or nothing);
`tapsSpaced` (TapBridge.kt:175, 120 ms) whenever the same cell may come twice within 0.25 s. `pad` = the latest describe the app holds
(`questsPad`, CompanionHost.kt:1652). Columns come from `partycfg.pad`, never from constants. Every batch already waits for TapQuiet and
the live gate. Taps: Touch = `[party to tgt+i]` or `[party to tsp+i]`; (-) tag = `[party to on]`; red Home disc / leaving Home =
`[party to off]`; long press = `[party to menu+i]`; menu entry run = Quests' digits (`PlayerMenu.digitCols`, PlayerMenu.kt:146, on
`qact`); menu Set Focus = `[party to focus+i]`; menu closed = `[party to mdone]` (only if the menu was opened by the PartyPad, i.e.
`partybox.pm`); settings: section 4.

---------------------------------------------------------------------------------------------------------------------------------

## 3. THE ROUTER (PadMap.lua, Spells; m77)

### 3.1 The one change to `PRE` (the fight-spell snippet: keep it this small)

`PRE` is PadMap.lua:909-1100. Two edits only:
1. After `local mode = self:GetAttribute("wc-mode")` (PadMap.lua:928):
   ```lua
   -- m77 PARTY MODE: while "wc-party" is set (and no room holds: room mode first) every press goes to the party pad, as room mode delivers
   local pdest = mode ~= "room" and self:GetAttribute("wc-party") and self:GetAttribute("wc-party-dest") or nil
   if pdest then mode = "room" end
   ```
   Setting `mode = "room"` locally turns off, by the existing gates, every other branch: fight spells, fishing, Smart Y, the quest item,
   the D-pad toolbar, slot fight spells / macros, B's give-back (PadMap.lua:934, 954, 960, 970, 975, 987, 1001, 1013, 1020).
2. PadMap.lua:1027-1029 becomes `if mode == "room" and (pdest or self:GetAttribute("wc-room-dest")) then dest = pdest or
   self:GetAttribute("wc-room-dest"); mb = self:GetAttribute("wc-key") or mb; fight = false end`.
The press then leaves as the existing `native` kind: `/click WowCompanionPartyPad <key> 1` on the key-down and `... 0` on the key-up
(PadMap.lua:1084-1098), the key's name as the mouse button (`wc-key`, PadMap.lua:1203).
ROOM MODE WINS: Interact's fight flip sets `wc-mode = "room"` on the 8 router buttons (F.FLIP, Interact.lua:5331-5338; F.ROUTER
5350-5352), and `pdest` is only taken when `mode ~= "room"`. With `wc-party` unset nothing changes: `tools/test_smarty.py` must pass
UNCHANGED.

Attributes set out of combat on all 8 router buttons (`routerAll()`, PadMap.lua:1336-1342; B and D-pad made by `routerB` / `routerD`,
1288-1298, which Party mode always calls): `wc-party-dest = "WowCompanionPartyPad"` (static); `wc-party` = true / nil (flipped only by
the secure snippets of 3.2, or out of combat by PadMap itself).

### 3.2 The new secure frames (made at load, out of combat, never destroyed; names are the contract)

| frame | template | job |
|---|---|---|
| `WowCompanionPartyPad` | SecureActionButtonTemplate, `RegisterForClicks("AnyDown","AnyUp")`, OnClick wrapped by `R.hdr` (PadMap.lua:1124-1146) with `PARTYPAD` | every Party mode press |
| `WowCompanionPartyToggle` | SecureActionButtonTemplate, `RegisterForClicks("AnyDown")`, wrapped with `PARTYFLIP`, `wc-set = "toggle"` | Select's binding |
| `WowCompanionPartyOn` / `WowCompanionPartyOff` | the same, `wc-set = "on"` / `"off"` | party row cols 10 / 11 |
| `WowCompanionPartyMenuDone` | the same, wrapped with a 1-line snippet clearing `wc-pmenu` on the pad | party row col 35 |

Frame refs (out of combat, refreshed whenever a router button is made, like `R.linkAll`, PadMap.lua:1214-1224): every one of these gets
`wc-r-1..wc-r-8` (the 8 router buttons, F.ROUTER's order) + `wc-rn = 8` + `wc-pad` (the PartyPad). `PARTYFLIP` sets `wc-party` on the 8
buttons and on the pad (toggle reads the PAD's current value), and clears the pad's `wc-pmenu`. The PartyPad's attributes:

| attribute | value |
|---|---|
| `wc-party` | the truth of Party mode (true / nil). NOT saved: every login starts OFF |
| `wc-box` | 1..n, kept for the session (first ON = 1, you) |
| `wc-pmenu` | true while the app's player menu (opened by Y) has the presses |
| `wc-u` | the unit the last menu / cast was for (read by the PostClick) |
| `wc-k` | the last press's kind (section 1.4) |
| `wc-ps-<ly><b>` | `wc-ps-a`, `wc-ps-x`, `wc-ps-zla` ... `wc-ps-zry`: the slot's spell NAME (cast by name = best rank), nil = Empty or not learned |
| `wc-py` | Y's in-combat spell name, nil = Inspect |
| `wc-pb-<unit>` | Smart Y's party pick per unit token (`wc-pb-player`, `wc-pb-party1` ...), out of combat only, nil = all up / Smart Y off |
| `wc-lt`, `wc-rt` | the trigger indexes, as routerWire (PadMap.lua:1201-1202) |

### 3.3 `PARTYPAD` (the snippet's behaviour, per button and layer)

Runs on the key-DOWN only (`if not down then return end`: the key-up changes nothing, so whichever edge the template acts on, it acts
once). At the start: `type`, `spell`, `unit` = nil, `wc-k = "none"`. `units` = `"player"` then each `partyN` (N = 1..4) with
`UnitExists`; `n = #units`; `box` clamped to n; `u = units[box]`.

| press | menu sub-state on (`wc-pmenu`) | otherwise |
|---|---|---|
| D-pad | `wc-k="key"` (the app moves its menu cursor) | `box = NAV(n, box, dir)` (3.4); `wc-k="move"`. Never a game action |
| B (PAD2) | `wc-k="key"` (the app steps back / closes) | Party mode OFF: `wc-party` nil on the 8 refs and the pad; `wc-k="off"` |
| ZL + ZR both held | nothing | nothing (`k="none"`) |
| A / X, any layer; Y with ZL or ZR | `wc-k="key"` | spell = `wc-ps-<ly><b>`; if set: `type="spell"`, `spell`, `unit=u`, `k="cast"`; nil: `k="none"` |
| Y, no trigger, IN combat (`[combat]`) | `wc-k="key"` | `wc-py` and `SecureCmdOptionParse("[@u,help,nodead] 1") == "1"` -> cast it on u; else MENU |
| Y, no trigger, OUT of combat | `wc-k="key"` | `wc-pb-<u>` and `[@u,nodead]` -> cast it on u; else MENU |

MENU = `wc-pmenu = true`, `wc-u = u`, `k = "menu"`: no game action. The PostClick then calls `WC.pmenu.read(u, "party")` (a plain read,
out of combat and in) and sends `partybox`. Layer: `ly = "zl"` if LT held, `"zr"` if RT held (from `GetGamePadState().buttons` with
`wc-lt` / `wc-rt`, exactly as PRE reads them, PadMap.lua:911-918). The fallback for Y covers absent / dead / ghost / not assistable only;
out of range, line of sight, mana, cooldown and unlearned spells are the game's own errors (FEASIBILITY 6). The cast never changes the
game's target (`CastSpellByName(spell, unit)`): the soft-target ruling.

PostClick (insecure, plain, in fights too): read `wc-k`, `wc-box`, `wc-u`, `spell`, `wc-party`, `wc-pmenu` -> `partybox`, flushed. Out of
combat only, after `on` / `off`: `routerBind("party")` (3.5).

### 3.4 THE BOX'S MOVES: `NAV(n, from, dir)` (identical in Lua and Kotlin; both test this table)

**SUPERSEDED (PadMap m129, the user's decisions 2026-11-11; E:/wow-build/party_design.md A.3):** one column, you first. Up / Down
move the pick one row with no wrap (`from - 1` / `from + 1`, clamped to 1..n); Left / Right never move it (kept for raid navigation
later: "this will need to support raids at some point"), and nothing can be put on them. The table below is the old grid's, kept for
the history only; tools/test_party.py, PartyModelTest and PartyLayoutTest test the column.

Layout by n (index 1 = you): n1 `[1]`; n2 `[1] [2]`; n3 `[1] [2 3]`; n4 `[1] [2 3 4]`; n5 `[1] [2 3] [4 5]`. Rows are walked by Up / Down,
cells by Left / Right; no wrap; "-" = no move (the app dims that arrow).

| n | from | Up | Down | Left | Right |
|---|---|---|---|---|---|
| 1 | 1 | - | - | - | - |
| 2 | 1 | - | 2 | - | - |
| 2 | 2 | 1 | - | - | - |
| 3 | 1 | - | 2 | - | - |
| 3 | 2 | 1 | - | - | 3 |
| 3 | 3 | 1 | - | 2 | - |
| 4 | 1 | - | 2 | - | - |
| 4 | 2 | 1 | - | - | 3 |
| 4 | 3 | 1 | - | 2 | 4 |
| 4 | 4 | 1 | - | 3 | - |
| 5 | 1 | - | 2 | - | - |
| 5 | 2 | 1 | 4 | - | 3 |
| 5 | 3 | 1 | 5 | 2 | - |
| 5 | 4 | 2 | - | - | 5 |
| 5 | 5 | 3 | - | 4 | - |

The Lua copy lives once as `OV.partyNav` between `-- PARTY-NAV-BEGIN` / `-- PARTY-NAV-END`; the PARTYPAD string carries the same body
(the harness checks both against this table). A box past n (someone left) clamps to n.

### 3.5 Bindings (`routerBind`, PadMap.lua:1512-1625; `routerCheck` 1627-1672; the fight events 3235-3247)

Out of combat, nobody holding (`aside` nil, PadMap.lua:1518-1520):
- Party mode ON: all 8 router keys are carried (`carry`, PadMap.lua:1536-1541): faces 5 / 6 / 8 WHATEVER their fight spell, B, D-pad 1-4
  (`routerD`). The m34 chord bindings (`bindPlan`, 1498-1509) are NOT bound while Party mode is on (a ZL+A binding would beat the chord).
- Party mode OFF: today's plan, unchanged.
- SELECT (`PADBACK`, the key of the game's `TOGGLEUIFOCUS`): carried to `WowCompanionPartyToggle` (on or off) unless an unfocused dialog
  waits (`WC.dialogs.unfocused() > 0`, the check Interact.lua:5193-5198 makes) -> `sel = "dialog"`. Checked again every `routerCheck`.
At a fight's start (`routerBind("fight", true)`, PLAYER_REGEN_DISABLED, before the lockdown; PadMap.lua:3235-3241): today's carry of every
key (1542-1548, 1606-1616) stands, so Party mode can be switched on mid-fight. SELECT is carried only while `IsInGroup()` OR Party mode is
already on, and no unfocused dialog waits (it costs the game's UI-focus key for that fight: FEASIBILITY 2); else `sel = "off"`.
Aside (a room / guest holds, a game window): nothing of Party mode is bound; Party mode stays as it is (SUSPENDED, `aside` in the doc).
A room taking the controller in a fight wins through room mode (3.1). Nothing is saved; overrides go at logout as today.
Select ownership: the bar selector RETIRES (user ruling). Interact's Select listener (capture v5, Interact.lua:5175-5208) stays in the
add-on unchanged but is never wanted again: the app stops sending `iact sel on` (section 4.7). Quests' guests that keep PADBACK while they
hold (Quests.lua:1761-1788: home, chat, bar) are unaffected: the router is aside then.

### 3.6 The per-character store and the class defaults

`WowCompanionSpellsDB.partyBy[name-realm]` (key = `whoKey()`; no character yet = a scratch table, never saved, as faceStore,
PadMap.lua:623-633):

```lua
partyBy["Korvash-Realm"] = {
  v = 1,
  slots = { a = 331, x = false, zla = 526 },   -- spell ID | false (Empty, chosen) | nil (class default)
  yc = 331,                                    -- nil / false = Inspect
  touch = nil,                                 -- nil = Target, else spell ID
  buffs = { 131 },                             -- Smart Y's party list, priority order, max 8, spell IDs
}
```
Stored by ID, resolved to the NAME at apply time (`C_Spell.GetSpellName`) and cast by name (best rank), as Smart buffs do
(PadMap.lua:3732-3734). Party mode's on / off is never stored. Smart Y's on / off stays `smartOn[name-realm]` (PadMap.lua:3634-3648).

`OV.PARTY_DEFAULTS` (class token -> slot -> spell ID; a default is drawn even before it is learned, `k=false`, never cast):

| class | a | x | zla | zlx | zly | zra | zrx | zry |
|---|---|---|---|---|---|---|---|---|
| SHAMAN | 331 Healing Wave | 8004 Lesser Healing Wave | 526 Cure Poison | 2870 Cure Disease | - | 2008 Ancestral Spirit | - | - |
| PRIEST | 2050 Lesser Heal | 17 Power Word: Shield | 527 Dispel Magic | 528 Cure Disease | - | 2006 Resurrection | 139 Renew | - |
| DRUID | 5185 Healing Touch | 774 Rejuvenation | 8946 Cure Poison | 2782 Remove Curse | - | 20484 Rebirth | 8936 Regrowth | - |
| PALADIN | 635 Holy Light | 19750 Flash of Light | 1152 Purify | 4987 Cleanse | - | 7328 Redemption | 633 Lay on Hands | - |
| MAGE | 475 Remove Lesser Curse | - | 604 Dampen Magic | 1008 Amplify Magic | - | - | - | - |
| WARLOCK | 5697 Unending Breath | - | - | - | - | - | - | - |
| HUNTER, ROGUE, WARRIOR | - | - | - | - | - | - | - | - |

`yc` and `touch` have no class default (Inspect / Target). Resurrection set `OV.PARTY_RES = {2008, 2006, 7328, 20484}` (`res=true`).
Party buffs known (`sy.known`, the Add chooser): `OV.PARTY_BUFFS = {1243 PW:Fortitude, 14752 Divine Spirit, 976 Shadow Protection,
6346 Fear Ward, 1126 Mark of the Wild, 467 Thorns, 1459 Arcane Intellect, 604, 1008, 19740 BoMight, 19742 BoWisdom, 20217 BoKings,
19977 BoLight, 1038 BoSalvation, 20911 BoSanctuary, 131 Water Breathing, 546 Water Walking, 5697 Unending Breath, 132 Detect Lesser
Invisibility}` filtered to the book's known spells by name (as `OV.sbKnown`, PadMap.lua:3746-3760). The `spells` chooser list = the
book's bindable spells (`WC.spellsBindableList`, Book.lua:1263-1276) with `C_Spell.IsSpellHelpful(id) == true` (plain), minus Smart
buffs' self-only IDs (`OV.SB_IDS` not in `OV.PARTY_BUFFS`, PadMap.lua:3711-3729), plus every known `PARTY_DEFAULTS` / `PARTY_BUFFS` spell.

Apply (out of combat; in a fight it is marked pending for PLAYER_REGEN_ENABLED): the pad's `wc-ps-*` / `wc-py` from the store or the
class default (known spells only), the party row's TSP cells by re-running `secureRow("party", ...)` (TapPad re-inits every cell),
then `partycfg`. Smart Y's party picks (`wc-pb-<u>`): only while Smart Y is on; out of combat; the first buff of `buffs` that this
character knows and that `u` lacks (`C_UnitAuras.GetAuraDataByIndex(u, i, "HELPFUL")` names, plain out of combat; a secret read = no
pick); on UNIT_AURA (player / partyN), GROUP_ROSTER_UPDATE, SPELLS_CHANGED, PLAYER_REGEN_ENABLED, a list change, Smart Y on / off;
coalesced 0.3 s. Food / water and self buffs stay Smart Y's own (yourself, nothing targeted, Party mode off); the party list is separate.

### 3.7 Rooms and other owners

Room mode first (3.1). Party mode is never turned off by the add-on when a room or window takes the controller; it is suspended
(`aside`). The app turns it off when Home leaves its rest page (4.2). Start (PADFORWARD) stays the pause wheel's (PadMap.lua:1581-1595).
LB / RB stay the game's: nothing of Party mode binds or taps them.

---------------------------------------------------------------------------------------------------------------------------------

## 4. THE APP

### 4.1 Pure model classes (no Android types, JVM-testable like PlayerMenu.kt; geometry in a plain `Box(l, t, r, b)`, never RectF)

| file (new) | contents |
|---|---|
| `PartyModel.kt` | data classes + `parse()` for `party`, `partycfg`, `partybox`, `partyack` (an older add-on: null, never a crash); `nav(n, from, dir)` (3.4); `unitIndex(u)`; the newest-`n` rule; stale-tap rule (`rs` changed < 0.5 s ago) |
| `PartyLayout.kt` | every rect of 4.2 for (state, n): module well, cells, tag, chat top; cell inner parts (bars, power line, role, name lines, %) |
| `PartyStrip.kt` | what the strip shows in Party mode: per face / layer the spell or Empty, lit / dim, Y's picture, the trigger plates' icons, the dim arrows |
| `PartyTaps.kt` | cell lists for every action from `partycfg.pad` (null when a column is missing) |

### 4.2 Home: the module (HomeView.kt)

A new `Part.PARTY` (HomeView.kt:74) and child view `PartyModuleView.kt` (new) in `views` (HomeView.kt:90-93), placed by `places()`
(HomeView.kt:1079-1150). The module is NEVER hidden (on, off, solo). All px on the 1240 x 1080 screen; the left column x 8..615; cells
x 20..603 (583 wide); side-by-side cells 13 apart; the well = 24 + cells; chat from the well's foot + 8 to 927 (y 927 = h - g today).
The well's top is the left column's top (216 on the device: `barsBottom + MODULE_GAP`, HomeView.kt:1101-1103, 1338-1342). With the
focused quest set ABOVE CHAT (HomeView.kt:1134-1150), the module goes under the card and above chat.

AT REST (out of combat), Party mode on or off:

| n | you | the others | well |
|---|---|---|---|
| 1 (solo) | 583 x 102 (y 228..330) | - | 126 (216..342) |
| 2 | 583 x 102 (228..330) | 583 x 102 (341..443) | 239 (216..455) |
| 3 | 583 x 128 (228..356) | 2 x 285 x 100, x 20..305 / 318..603, y 367..467 | 263 (216..479) |
| 4 | 583 x 128 (228..356) | 3 x 185 x 100, x 20..205 / 218..403 / 416..601, y 367..467 | 263 (216..479) |
| 5 | 583 x 128 (228..356) | 2 x 2 of 285 x 100, y 367..467 and 478..578 | 374 (216..590) |

IN A FIGHT, Party mode ON (v17): NO takeover (HomeView.kt:900 becomes `flow.combat(grow.shown(now) && !party.onLive)`; switching on
mid-fight shrinks the takeover back with the existing COMBAT_SHRINK motion, HomeView.kt:1244-1251). Same place, every row 150 high,
pitch 161: you 583 x 150, the others as at rest but 150 high. Well = 24 + rows x 150 + (rows - 1) x 11: solo 174, n2-4 335, n5 496
(216..712); chat from the foot + 8 (n5: 720..927).

IN A FIGHT, Party mode OFF (v18): today's takeover (y 0..397). The module at the TOP of the left well, from y 398: you 583 x 100
(y 410..510), the others in ONE row 100 high (y 521..621): 1 = 583, 2 = 285, 3 = 185, 4 = 136 wide (x 20..156 / 169..305 / 318..454 /
467..603: the drawing; the text's "140" does not fit 583). Well 235 (398..633), chat 641..927. Solo: your cell 102 (well 398..524).
The map module x 624..1232 from y 398.

THE (-) TAG: only while Party mode is OFF (or suspended): 208 x 42 standing on the well's top edge at its right end (at rest x 392..600,
y 194..236; in a fight x 392..600, y 378..418); ground (27,22,16), edge (74,58,28) 2 px, corner 8; `rev_minus` 36 px at x +28; "Party
mode" Friz 26 gold from x +54. A TAP on it = Party mode ON (party row `on`).

THE CELL (the game's raid-style frame): ground (8,8,8); health bar x 2..w-1, y 2..h-13, kit `m_raidframe_hp_fill` in the CLASS colour
(`col`, else `Theme.classColourOf(c)`, Theme.kt:95-119) on (26,26,26), its width = `hp` %, its last 40 px a linear fade to the ground
(no fade when `full`); power line y h-7..h-1 (6 px, a 4 px dark gap above), FULL width, the type colour only: MANA (0,0,255), RAGE
(255,0,0), ENERGY (255,255,0), FOCUS (255,128,64), else (128,128,128). Role 46 px at (6, 8) on full-width cells only (kit
`role_small_tank` / `_heal` / `_dps`; NONE = nothing). Name white: full-width rows Friz 30 from x 50 (centred y 31, room w - 140);
YOUR big cell (n >= 3) Friz 34; small cells Friz 28 from x 12 (room w - 26); too long: smaller first, under 22 px -> two lines (Friz <= 26
at y 22 / 48, % then Arial Narrow 26 at y 74); never cut. "~NN%" Arial Narrow 28 (big 32), right, 14 in (grid: 12 in, y 68). Words:
the game's shadow + a 1 px dark edge. DEAD: bars empty on (48,46,44), skull (kit `t_ui_raidtargetingicon_8`) 34 px at (10,14) in the
role's place, name (200,200,200), "Dead" Friz 26 (190,190,190) centred y 66. OFFLINE (lead's call, not drawn in the spec): the cell at
50 %, "Offline" in place of the %. Order: bars, then the box (`UiKit.focus(c, cell)`, only while on), then the words.
MISSING KIT ART (`m_raidframe_hp_fill`, `m_raidframe_resource_fill`, `t_ui_raidtargetingicon_8`, `t_cursor_point`): UI Art adds them to
the kit; until `UiKit.tex(name)` has them, flat fills and a drawn skull glyph. Never a crash.

THE BOX: the add-on's `box` is the truth. Optimistic: while Party mode is on, live, Home shown and no room holds, the app moves its box
at once from its own pad reader (PadHub, PadInput.kt:127) with the same `nav`, and the newest `partybox` corrects it.

HOME'S DISC: red with B while Party mode is on (`HomeView.bGoesHome()`, HomeView.kt:251, gains `|| party.onLive`; the strip's look,
Strip.kt:106-112). A touch on it while on Home in Party mode = party row `off` (a new first branch in `swirlTouched`,
CompanionHost.kt:1212-1225), not the pause wheel.

LEAVING HOME'S REST PAGE (another room, or a Home module full screen) while Party mode is on: the app taps `off` first. On while
`aside` (suspended): drawn as OFF (no box, tag shown).

### 4.3 The strip (CastView.kt)

Party mode OFF: today's strip, untouched. Party mode ON (at rest, and in a fight: no takeover): positions are today's (`drawBand`,
CastView.kt:2515-2571); the party pictures enter the override chain first (CastView.kt:2522: `partyOverride(li, src) ?: ...`).

| place | Party mode ON |
|---|---|
| D-pad slots (left (193,64), up (289,13), right (384,64), down (289,113), 88 px) | emptied; the game's gold arrow inside (kit `move_arrow_*`, turned), 58 px max; an arrow whose NAV is "-" at 28 % |
| A (970,109), X (888,59) | the slot's spell, 74 px cut round in today's ring, glyph badge 36 px at centre + (26,-22). Empty: the ring alone at 45 % |
| Y (805,109) | out of combat: the game's own Y (Inspect) picture, or with Smart Y on the buff of `sy.pick[u]` (badge on top); IN a fight: `yc`'s icon, or the Inspect picture when `yc` is null |
| B (888,158) | unchanged (Back): closes Party mode |
| LB / RB plates | unchanged (the game's) |
| ZL / ZR trigger plates (v13) | 126 x 78 in Home's well look (ring edge 6 px): ZL x 14..140, ZR x 1100..1226, y 124..202; `rev_ltrigger` / `rev_rtrigger` 50 px centred 34 in; the layer's set chord spells as 30 px round icons 33 apart from x +66 (Empty not shown). Held: the plate lit (SOFT_GOLD alpha 120, blur 6) and A / X / Y show that layer (Empty = ring alone), B stays Back |
| lit / dim | the boxed member DEAD: only `res` spells lit; ALIVE: `res` spells dim; not learned (`k=false`): dim; Y and B never dim |

Today's ZL / ZR trigger strip (`drawTriggerStrip`, CastView.kt:2581) is not drawn in Party mode; the held state comes from CastView's own
reader (`padLt` / `padRt`, CastView.kt:473).

### 4.4 The Controller room's Party mode tab

TABS gains `"party"` (PadMapModel.kt:525-527: `tabOf("party") = 5`), `TAB_NAMES` gains "Party mode" (ControllerView.kt:643), the tab
tap = `padtab` 26. Drawn by a new `PartyTabPage.kt`, called by ControllerView when `view == "party"` (PadTabPage.kt keeps the other four).
The CURSOR is the add-on's, as on every tab: PadTabCtl (Spells, the ADD-ON worker) adds `"party"` to TABS (PadTabCtl.lua:54), ORDER
`party = { "tabs", "prows", "pright" }`, and `padtabctl` gains `ppos` (1..12, below), `pfocus` (`"rows"` / `"right"`), `pright` (row
on the right page), and a chooser with `list = "party"` whose rows are the pseudo row first (`"empty"` / `"inspect"` / `"target"`) then
`partycfg.spells` by id.

Left page, two columns of six rows, 111 high from y 188, row rects x 36..362 and 374..700 (v10): LEFT `ppos` 1 D-pad (fixed, lock,
"Moves the box" / "Fixed"), 2 A, 3 X, 4 Y, 5 Touch, 6 empty place; RIGHT 7 ZL+A, 8 ZL+X, 9 ZL+Y, 10 ZR+A, 11 ZR+X, 12 ZR+Y. A chord's glyph
= the trigger's (50 px) over the face's (34 px) at centre x +30, cy - 17 / cy + 20. Row = glyph, the spell in `book_frame` 58 px, name
Friz 27, one line Friz 22 ("Boxed member" / "A dead member" for res / "Empty"); no button on a row; the chosen row = paperGold. The Y row:
"Inspect" (Smart Y off) or "Smart Y" (on), its line "In combat: <yc or Inspect>". Touch row: the pointing gauntlet glyph, the target
ring (or the spell) in its frame, "Touch" / "Target". D-pad: Up / Down in a column, Left / Right across at the same row.
Right page (v9 trim: titles, icons, the current value, ONE red action x 790..1172, y 804..892): a spell row: its icon 96, name, "A, in
Party mode"; Change. Y with Smart Y OFF (P4Y): "Out of combat" Inspect; "In combat" + the spell with the crossed swords; Change (the
In combat action). Y with Smart Y ON (P4S): "Party buffs": the list rows (111 high from y 410), the Add row, and (lead's proposal, open
question 2) a last row "In combat: <yc>"; the red action follows the chosen right-page row: Remove / Add / Change. Touch: "Now" + Target
or the spell; Change. D-pad row: Change disabled.
By touch: `padtab` 27-36 picks, 37 the no-spell value, Book's two-tap spell (2.3); party-buff Remove / Add use the party row (2.2).

### 4.5 Settings > QOL > Smart Y: the Party mode sub-section (SettingsView.kt)

Shown only while Smart Y is on (`SmartYModel.rowsShown`, SmartYModel.kt:98), after today's lines (`sbLines`, SettingsView.kt:722-742;
`drawSmartY`, 755+): a sub-heading "Party mode (the boxed member)" Morpheus 27 gold, one line Friz 22 dim; rows "1. Water Breathing" /
"Cast on the boxed member when it is not up." (numbered, today's `row()` look, 96 high, 10 apart); a chosen row gets today's action line
with Up / Down / Remove; "Add a party buff" / "One of your buffs you can cast on others." [Add] -> the `sy.known` list (minus listed) ->
Pick. Taps: select = `[party to pbsel+k-1]` then the op col, ONE batch; Add = `[party to pbops.add] + smartSpellCells(name)`
(SettingsView.kt:715, CompanionHost.kt:708). The answer is `partyack op "pb"`; the list shown is always `partycfg.sy.list`.

### 4.6 Touch, long press and the player menu

A cell takes touches with Party mode on OR off, exactly the same; a touch NEVER moves the box. TAP (up within 450 ms, no move > 24 px):
`touch == null` -> `[party to tgt+i]`, else `[party to tsp+i]`. LONG PRESS (450 ms, the class wheel's `CLASS_LONG_MS`, HomeView.kt:356,
2430) -> the player menu for that unit: `[party to menu+i]`, and the menu opens "reading". Refused for 0.5 s after an `rs` change.
The menu: a new `PartyMenuView.kt` over the left column (x 24..600, y 236..914), `UiKit.menu(c, r, solid = true)` (UiKit.kt:2650), the
member's name as heading (Friz 28 gold at +16, +40), rows 80 high, labels Friz 28 from x +100, section titles Friz 22 dim, the chosen row
`UiKit.focus` with its word gold, sub-menus "  >", more rows scroll with the gold arrow at the foot. Model = `PlayerMenu` (PlayerMenu.kt),
extended: `Entry` gains `secureRow` (`"party"` / `"pfocus"`) and `app` (`"whisper"`); `Do.Secure` carries the row. Greyed: what the game
greys, Inspect (taint), and unknown keys. A runs (qact digits), B goes back a level or closes, as the map's menu (MapView.kt:3760-3800).
Driven by: the PartyPad's `partybox k="key"` presses while it was opened by Y in Party mode (close = `[party to mdone]`); by touch
always; with Party mode off, by touch only (lead's rule; the controller stays the game's).

### 4.7 Routing and the retired bar selector (CompanionHost.kt, HomeBarSelect.kt)

`handleMessage` (CompanionHost.kt:1545+) gains `party`, `partycfg`, `partybox` (current only), `partyack` (current only); `pmenu` with
`src == "party"` goes to the party menu, else to the map as today (CompanionHost.kt:1623). The resend rule of 1.1.
The bar selector loses Select: `HomeBarSelect.onInteract` (HomeBarSelect.kt:93-112) never taps `sel on` again; when an interact document
says `sel.want == true` (a stale want) it taps `sel off` once (`selOffCol`, as `appAway` does, HomeBarSelect.kt:177-182). The `cappad
room=bar` route (CompanionHost.kt:1858) stays harmless. Bar commands (`barcmd`, D-pad Down) keep the bar cursor.

---------------------------------------------------------------------------------------------------------------------------------

## 5. WHO OWNS WHAT, THE ORDER, THE TESTS

### 5.1 File ownership (no file is edited by both)

| ADD-ON worker | APP worker |
|---|---|
| `addons/WowCompanionSpells/PadMap.lua` (router, PartyPad, store, docs, estimate, Smart Y party) | `PartyModel.kt`, `PartyLayout.kt`, `PartyStrip.kt`, `PartyTaps.kt` (new) |
| `addons/WowCompanionSpells/PadTabCtl.lua` (the Party tab's cursor, chooser, puts) | `PartyModuleView.kt`, `PartyMenuView.kt`, `PartyTabPage.kt` (new) |
| `addons/WowCompanionSpells/Book.lua` (padtab 26-37) | `HomeView.kt`, `CastView.kt`, `CompanionHost.kt`, `HomeBarSelect.kt` |
| `addons/WowCompanion/TapPad.lua` (pad11: two constants) | `ControllerView.kt`, `PadMapModel.kt` (TABS / tabOf), `SettingsView.kt`, `PlayerMenu.kt` |
| `addons/WowCompanionQuests/Quests.lua` (`WC.pmenu.read(unit, src)`, `actFor(key, text, unit)`, party entries) | `app/build.gradle.kts` (version) |
| `tools/test_party.py` (new) | `dsforever/src/test/.../PartyModelTest.kt`, `PartyLayoutTest.kt`, `PartyStripTest.kt`, `PartyTapsTest.kt` (new), `PlayerMenuTest.kt` |

Read-only for both: everything else, including `tools/test_smarty.py` (it must pass unchanged), Interact.lua, Dialogs.lua, Hub.lua,
Radial.lua, Chat.lua, TapBridge.kt, UiKit.kt, Theme.kt, SmartYModel.kt, MapView.kt, PadTabPage.kt. Need a change there: ask the lead.
`PARTY_BUILD.md` is the lead's.

### 5.2 Versions

Add-on: PadMap `PV = "m77"` (PadMap.lua:24), PadTabCtl `CV = "p33"` (PadTabCtl.lua:36), Book `BV = "b47"` (Book.lua:30), TapPad
`PADV = "pad11"` (TapPad.lua:83), Quests `QV = "q49"` (Quests.lua:27); each with a one-line note in its comment. Interact, Hub, Radial,
Chat unchanged. App: `versionName = "1.0.13-party1"`, `versionCode = 1211` (app/build.gradle.kts:58-59).

### 5.3 Order of work (both start at once: the documents and columns above are frozen)

ADD-ON: (1) TapPad pad11 + its test; (2) the PRE edit + `test_smarty.py` green; (3) PartyPad / flip / menu-done frames, PARTYPAD,
PARTYFLIP, NAV + snippet tests; (4) routerBind / routerCheck / fight-start rules + Select; (5) store, defaults, apply, the party row;
(6) `party` / `partycfg` / `partybox` / `partyack`, heartbeat ride, resend, the estimate; (7) Smart Y party picks, PB ops, `smartYSpell`
op `padd`; (8) PadTabCtl Party tab + Book padtab 26-37; (9) Quests pmenu for units. Commit per step.
APP: (1) PartyModel parse (from the JSON in section 1) + NAV; (2) PartyLayout; (3) PartyTaps; (4) Home module + HomeView places + no
takeover in Party mode + red disc + tag; (5) CastView party strip + trigger plates; (6) touch, long press, PartyMenuView, PlayerMenu
extension; (7) CompanionHost routing + resend + off-on-leave; (8) HomeBarSelect retirement; (9) the Controller tab; (10) Settings
sub-section; (11) version. Commit per step.

### 5.4 Offline tests

ADD-ON, `python tools/test_party.py` (lupa, the `tools/test_smarty.py` harness style: stubbed game, secure snippets run as plain Lua with
`mkframe`, exit 1 on any failure). Each group proves:
1. PRE + party: with `wc-party` set, each of the 8 router buttons builds `/click WowCompanionPartyPad <key> 1` (down) / `... 0` (up) with
   no fight spell / fish / Smart Y / quest item / toolbar / give-back; `wc-mode = "room"` beats `wc-party`; unset = today (and
   `python tools/test_smarty.py` passes unchanged).
2. PARTYPAD: every NAV row of 3.4 (and `OV.partyNav` the same); A / X / chords cast the right name on the right unit (solo = player);
   both triggers = nothing; Empty = nothing; Y out of combat: pick (alive) / menu (dead, no pick); Y in combat: `wc-py` on a `help,nodead`
   member, menu otherwise; B = off on all 8 refs; menu sub-state swallows every press as `key`; MENU DONE clears it; key-up changes nothing.
3. PARTYFLIP: toggle / on / off from both states; clears `wc-pmenu`.
4. Bindings (a binding table mock that also SPIES): Party on = 8 keys + Select carried, no m34 chords; off = today's plan; Select left
   alone with an unfocused dialog; fight start carries Select only when grouped or already on; aside = nothing; NEVER SetBinding /
   SaveBindings / CreateMacro / SetCVar / any Blizzard open-close function (the spy fails the run).
5. Combat-lockdown mock (as the Quests harness: any secure-frame SetAttribute / secureRow init from insecure code in combat errors):
   settings ops in a fight answer `partyack ok=false why="combat"` and apply at PLAYER_REGEN_ENABLED.
6. Store: two characters (name-realm) never share; nil = class default, false = Empty; defaults for all 9 classes; `k` follows the book.
7. Documents: `party` / `partycfg` / `partybox` / `partyack` match section 1 key for key; ride `heartbeat` / `login` / `zone` /
   `manual`; RESEND sends all three flushed; a roster change flushes and bumps `rs`.
8. The estimate (PARTY-EST block): new = full, wounds / heals (plain only), 6 s quiet anchor out of combat, dead 0, revive 50 %, no change
   on secret amounts.
9. Smart Y party: pick per unit, skips known-but-up, nothing in combat / Smart Y off; PB select + ops + add (`padd`) with the 2 s rules.
10. TapPad pad11: at popupTop 126.6 rows 0..55 placed and described; at 121.5 rows 0..53 only; the party row's columns per 2.2.
11. Quests: `WC.pmenu.read("party2", "party")` -> doc with `unit`, `which = "PARTY"`, `src`; actions use the unit; Inspect greyed; Set
    Focus maps to the party row's column; Whisper sets `WC.chatLastTell`.

APP, `gradlew.bat :dsforever:testDebugUnitTest` (JUnit + org.json, dsforever/build.gradle.kts:50-54), and the app compiling
(`gradlew.bat :app:assembleModernDebug`):
- `PartyModelTest`: section 1's JSON parses field for field; missing / older documents -> null or defaults; newest `n` wins; replays
  never produce taps; the stale-tap window after `rs`.
- `PartyLayoutTest`: every number in 4.2 for n = 1..5 x {rest, fight on, fight off}; the tag's two places; NAV = the 3.4 table.
- `PartyStripTest`: lit / dim (dead -> res only; alive -> res dim; `k=false` dim; Y / B never); the chord layer swap; Y's picture out
  of / in combat with Smart Y on / off; the dim arrows per (n, box).
- `PartyTapsTest`: every action's cells from `partycfg.pad`; null when a column is missing; Add / PB ops as ONE batch.
- `PlayerMenuTest` (extended): a party unit's entries, `Do.Secure` on the party row, `ap = whisper`, greyed Inspect.

### 5.5 Device probes owed (NOT now: each needs the user's go and a party member; FEASIBILITY P1-P10, plus)

P11: one tap on each of the party row's cells 0, 10, 11, 22 at the Thor's UI scale (pad11 rows 54-55 land). Fight-spell regression on
the device after the PRE edit (FEASIBILITY risk 4).

---------------------------------------------------------------------------------------------------------------------------------

## 6. OPEN QUESTIONS FOR THE USER (short)

1. Smart Y's party list starts EMPTY (Smart Y's "nothing is ever suggested", m45), or with the class's buff (the spec drew Water
   Breathing)?
2. With Smart Y ON, the Y row's right page shows the party buffs (P4S). Where is Y's In-combat action changed? Proposal: a last row
   "In combat: Inspect" on that page, its red action Change.
3. The box when Party mode turns on: on you the first time, then where it was last (this session)?
4. Party mode turns OFF when you leave Home or open a Home module full screen (proposal), rather than staying on in the background?
5. Select in a fight is taken only while you are grouped (or Party mode is already on): the game's UI-focus key is lost in those fights.
   OK?
6. The pad grows to 56 rows (fits the Thor's own UI scale; at the smallest UI scale the last bag rows drop). OK, or the bigger
   two-rows-per-row change?
7. In a fight with Party mode on, the spec sizes 5 members (150 high); 2-4 members use the same 150 rows. OK?
8. Kit art: UI Art adds the raid-frame bar fills, the skull and the pointing gauntlet to the kit; plain fills until then. OK?

---------------------------------------------------------------------------------------------------------------------------------

## ADD-ON DEVIATIONS (the ADD-ON worker, branch `feat/party-addon`; every document stays compatible with section 1)

Built: PadMap m77, PadTabCtl p33, Book b47, TapPad pad11, Quests q49, `tools/test_party.py` (groups 1-11 + the Party tab + the
no-trace scan). Where the contract could not be followed to the letter, the closest correct thing:

1. **JSON `null` = the key is ABSENT.** Safety.lua's encoder drops nil fields, so `aside`, `box` (Party mode off), `yc`, `touch`, a
   null slot (`zly` ...), `hpMax` / `pmMax` / `rng` when not plain, `why`, `sp`, `u` (before any press) are missing rather than
   `null`. The app treats a missing key exactly as `null`.
2. **An empty object encodes as `[]`** (the same encoder: an empty table is an array). `c.slots` with every slot Empty and no class
   default (a Hunter / Rogue / Warrior with nothing chosen) arrives as `"slots":[]`: read it as "all Empty". `sy.pick` is OMITTED (not
   `{}`) when no unit has a pick, which is always the case in a fight.
3. **`partyack` ops beyond the list:** `op "cell"` (why `"unknown column"`) answers the spare party cols 36-39; `op "touch"` (why
   `"target"`) answers a stale tap on a TSP cell (5-9) while Touch = Target. PB ops, Add and Remove all answer `op "pb"` with `id`.
4. **The flips' clicks:** `WowCompanionPartyOn` / `Off` / `MenuDone` are registered for BOTH edges (a `type="click"` cell clicks them
   with `Click(button)`, one edge whose `down` is not guaranteed); their snippets are absolute, so a repeat is harmless.
   `WowCompanionPartyToggle` (Select's key) is `AnyDown` only, as written. One `partybox` per flip (a 0.25 s de-dupe), whatever edge
   arrived, so "on the press (down) only" holds for the pad, not literally for a cell's click.
5. **The Party tab's chooser has a second list, `"pbuff"`** (rows = `sy.known` not listed yet, by id; no pseudo row): the controller's
   Add on Y's right page with Smart Y on. Y's right page rows with Smart Y on = the list's rows, then Add, then "In combat" (open
   question 2, the lead's proposal, taken). By touch the contract's route stands (PBSEL / ops / add on the party row).
6. **`padtabctl.ppos` / `pfocus` / `pright` are sent only while `view == "party"`** (absent on the other four tabs).
7. **`partybox.key` is the position's STANDARD name** (`PAD1` ... `PADDDOWN`) even when the player re-bound `GAMEPADACTIONBUTTONn`: the
   pad maps whatever key arrives through its own `wc-kp-<key>` attributes (set out of combat from the router's keys, the game's names as
   the fallback).
8. **NAV's direction names** (both copies, Lua and the snippet): `"up"` / `"down"` / `"left"` / `"right"`.
9. **The `party` document on an on / off is sent at once and flushed** (like a roster change), not coalesced; `rs` moves only on a
   roster change (and on a loading screen: a new world = everyone re-read).
10. **`full`** is true at the two anchors AND when a plain heal brings the estimate back to max.
11. **`sel = "off"`** also when Select could not be carried (the override did not take), so the app never shows a Select that is not ours.
12. **Settings never change in a fight, the party list included:** PB select is accepted (a pick), but Remove / Up / Down / Add answer
    `partyack op "pb" why "combat"`; a `padd` spell in a fight is refused the same way. Applies that were due in a fight (SPELLS_CHANGED,
    a login in a fight) run at PLAYER_REGEN_ENABLED.
13. **Quests q49:** Whisper is a RUNNABLE entry (`i`, the qact digits) whose run sets `WC.chatLastTell`; it also carries `ap
    "whisper"`, so the app runs it and then opens the Chat room. Promote / Uninvite carry `cf` (the app asks first). The self-only
    entries (PvP, loot, Reset instances, Leave) are never offered for a party unit (greyed). Read for Party mode, YOUR OWN menu's Set
    Focus is the party row's col 12 (`scr "party"`); the map's portrait keeps `pfocus` col 0. A run / a focus re-reads the SAME unit's menu.
14. **The router's header is made at load** (Party mode's frames need it; it used to be made at the first PLAYER_ENTERING_WORLD).
15. **THE ONE SWITCH** for open question 4 on the add-on side: `PM.OFF_WHEN_ASIDE = false` (PadMap, partyMode()): Party mode is only
    SUSPENDED while a room / a game window holds (`aside`); set true and the add-on itself turns it off then (out of combat). The app's
    own "off when leaving Home" (4.2) is the app's switch; the lead's default for it is: Party mode does NOT turn off.

Known limits (by the contract, recorded): a fight that STARTS with Party mode off keeps an m34 D-pad binding (set 1) on its key for
that fight, so switching Party mode on mid-fight leaves that one direction the binding's. TapPad's repeat guard (0.25 s) swallows the
same party cell tapped twice within 0.25 s: one op per batch, or `tapsSpaced`.

## APP DEVIATIONS (the APP worker, branch `feat/party-app`, 2026-10-28)

Documents, fields, pad11 columns and file ownership are unchanged. What the app does differently from the letter of this file, and why:

1. The user's defaults for the open questions, each one switch in code: Q2 `PartyTabPage.YC_ROW_IN_SMART_Y = true` (with Smart Y on,
   the Y right page ends with a row "In combat: <yc>"; its one red Change sets `yc`); Q4 `PartyModel.OFF_ON_LEAVE = false` (leaving
   Home or opening a Home module full screen does NOT tap `off`; the add-on's `aside` suspends Party mode, drawn as off); Q7 2-4 members
   in a fight use the 150 px rows (as 4.2); Q8 `PartyArt` (in PartyModuleView.kt) draws plain fills, a drawn skull, a drawn gauntlet
   and a drawn target ring until `UiKit.tex` has the kit pieces.
2. The (-) tag in a fight: 208 x 42 with its foot 20 under the well's top edge, so x 392..600, y 376..418 (4.2's "378..418" is 40 high).
3. The module's top is Home's real left-column top (`barsBottom + MODULE_GAP`, or under the card when the card is above chat), not a
   fixed 216 / 398: on the device those are the numbers; the rows, gaps and sizes are 4.2's exactly (PartyLayoutTest).
4. The module appears once a `party` document has come (shown from a replay too). An older add-on sends none: no module, no crash.
5. In a fight with Party mode on, the 150 px rows follow Home's own fight read (`HomeGrow.fighting`: quests `cb` / `hudS.c`), the same
   read that drives the takeover, not `party.fight`.
6. The resend (col 22) is tapped only with a known `partycfg.pad.resend` (a replayed partycfg counts for the column); before any
   partycfg the first-document ask waits for one (the heartbeat ride brings it within 15 s) instead of tapping a constant.
7. PlayerMenu: no new `Do` subclass (MapView, read-only, has an exhaustive `when` on `PlayerMenu.Do`). `ap = "whisper"` rides
   `Do.Run(id, text, app)` (id -1 = nothing to tap); `Do.Secure(col, text, row)` with row defaulting to "pfocus". Whisper also opens the
   Chat room with `chatView.whisperTo(nm)` (as Social's Whisper), besides Quests' `WC.chatLastTell`. Inspect is greyed by the app when an
   item's `k` (or text) is "Inspect" / "InspectUnit", on top of what the game greys.
8. The Controller tab's tab tap uses `partycfg.pad.ptab.tab` when a partycfg came, else `PadMapModel.COL_PARTY_TAB` (26, Book b47):
   the tab row exists before any partycfg. `padtabctl.tcur` is read 1..5.
9. The Controller tab by touch: the app shows its own chooser on the right page (the pseudo row first, then `partycfg.spells`; for
   Smart Y's Add: `sy.known` minus the list) and taps `padtab` pick + 37, pick + Book's two taps, or the party row's Add / Remove. The
   controller's chooser (`chooser.list == "party"`, `row` 1-based, row 1 = the pseudo row) is drawn from `padtabctl` with the same rows.
10. The five tabs' labels are fitted by ControllerView (drawn over `UiKit.tab` with an empty label: UiKit is read-only); the tab row now
    runs from the screen edges (it keeps its own L / R room).
11. Settings' sub-heading keeps ONE short line ("Out of combat, on the boxed member.") instead of P5's sentence (the terse-text rule).
12. The strip: an Empty face = the slot at 45 %; a not-lit spell = the slot's faded (`unable`) look; the trigger plates are drawn only in
    the diamonds band (Home's default rest look); the arrows / faces follow in the thin-row look too. An offline member's whole cell is
    drawn at 50 % with "Offline" for the %.
13. HomeBarSelect: a stale `sel.want` gets one `sel off`, armed again after a document without the want (`SELECT_RETIRED`).
14. The party menu closes when Home is left (with MENU DONE when the PartyPad's Y opened it), and when its member leaves the group.

For the ADD-ON worker: the app reads `padtabctl.c.view == "party"`, `ppos` (1..12, 4.4's numbering), `pfocus` ("rows" / "right"),
`pright` (0-based: the buff rows, then the Add row, then the In combat row while Smart Y is on), `chooser.list == "party"`; `partybox`
`k = "menu"` opens the menu for `u`, `k = "key"` with `key` = PADDUP / PADDDOWN / PADDLEFT / PADDRIGHT / PAD1 / PAD2 drives it while
`pm`, `k = "off"` closes it; `pmenu` with `src = "party"`, `nm`, items' `k`, `sc` + `scr`, `ap`. Every app tap's column is read from
`partycfg.pad` except the tab fallback above.

Render check (no device): `app/src/test/java/com/jrees/wowcompanion/PartyShotsTest.kt` (Robolectric native graphics), run only with
`PARTY_SHOTS_DIR` set (and `PARTY_ART_ROOT` = an art root such as a release export's `runtime`): the module's states, the strip, the
player menu and the Controller tab at real size. Home's other modules and the Settings sub-section are not drawn by it.

## INTEGRATION (the integration worker, branch `feat/party-mode`, 2026-10-28; commits 7368e333 add-on, e11d9295 app; the second ruling: see the git log)

Both halves read end to end, document by document and tap column by tap column, against the deviation lists above. Offline only.

### Mismatches found and how each was resolved

| # | where | mismatch | resolution |
|---|---|---|---|
| 1 | nil / `[]` | add-on drops nil fields and sends empty tables as `[]` (`"slots":[]` for a Warrior) | already safe in the app: `PartyModel` reads absent = null and `optJSONObject` on a `[]` = null (every slot Empty); `sy.pick` absent = no picks. Now PROVEN on the real encoder's output (fixtures below) |
| 2 | chooser lists | the add-on's controller Add (Smart Y on, Y's right page) opens chooser `list = "pbuff"`; the app only drew `"party"` (the controller's Add showed nothing) | ONE route per input, one set of rows, one end point: the CONTROLLER goes PadTabCtl chooser `pbuff` -> `PM.padd(id)`; the FINGER goes party col 34 + Book's two taps -> `WC.smartYSpell` op `padd` -> the same `PM.padd`. Rows on both = `sy.known` minus `sy.list`, no pseudo row. The app now draws `pbuff` (`PartyModel.CTL_LISTS = party, pbuff`; heading "Add a party buff") |
| 3 | `padtabctl.pright` | add-on sent it 1-based (1 = first right-page row); the app reads 0-based | the add-on sends `tc.pright - 1` (internal state unchanged). `ppos` 1..12, `pfocus` rows / right and the chooser `row` (1-based, row 1 = Empty / Inspect / Target for `party`; the first buff for `pbuff`) already agreed. Note: `pfocus` is `"rows"` also while the cursor is on the tab row; the app reads `focus == "tabs"` for that, so it holds |
| 4 | `partyack` | ops `cell` (spare cols, why `unknown column`) and `touch` (stale TSP tap, why `target`, no `slot`); every list op answers `op "pb"` + `id` | the app parsed them, but EVERY ack reached the Controller tab and Settings, so a `resend` ok closed a touch chooser and a stale Home touch said "The game did not take it" on the Controller tab. Now only settings answers reach the pages (`PartyModel.forSettings`: slot / yc / pb / touch WITH a slot); the rest are logged. Refusals stay terse ("Not in a fight: try again after it" / "The game did not take it") |
| 5 | TapPad 0.25 s guard | same cell twice within 0.25 s is swallowed | every app batch has distinct cells (pick + action, PBSEL 23-30 + op 31-34, col 34 + spells row vb / vo, padtab pick + 37); tested on the real `partycfg.pad` (`PartyFixturesTest.cfgFull`). Batches for different actions use different cells (menu run on `qact`, MENU DONE col 35, off col 11). No `tapsSpaced` needed |
| 6 | Select | | verified: `HomeBarSelect.SELECT_RETIRED = true`, no `iact sel on` anywhere in the app. Hardened: a stale `sel.want`'s `sel off` is tapped again at most every 10 s while the want stays (a lost tap would have left Interact's listener on Select) |
| 7 | `pmenu` q49 | | agreed: `src "party"` routes to PartyMenuView; `sc` + `scr "party"` -> `Do.Secure` on the party row (Set Focus col 12 + i), Clear Focus `sc 1` on `pfocus`; `cf` -> asked first (No under the cursor); Whisper has `i` AND `ap` -> `Do.Run(id, text, "whisper")`: digits, then the Chat room with `whisperTo(nm)`; Inspect `en 0` (and greyed by key). Tested on the real document |
| 8 | `partybox` | | agreed: `k "menu"` + `u` opens the menu by pad; `k "key"` with PADDUP / PADDDOWN / PADDLEFT / PADDRIGHT / PAD1 / PAD2 drives it while `pm` (Left / Right are no-ops in PlayerMenu); `k "off"` closes it; the document's `key` uses the STANDARD names, NAV's own `"up"`... names stay inside Lua. NEW: a STRANDED sub-state (`pm` true on a `key` / `state` press and no menu of the app's driving the pad: a lost `k=menu`, an app restart) made every press a `key` forever, B included. The app now taps MENU DONE then (`PartyModel.strandedMenu`) |
| 9 | the user's rulings | Q4 defaults: app `OFF_ON_LEAVE = false`, add-on `OFF_WHEN_ASIDE = false`, and the app only handled a ROOM (never a Home module full screen, a wheel, the bar cursor or a window), and only while `onLive` | see below |

Nothing else in the two deviation lists affects the other side (flip edges / de-dupe, `full`, `sel "off"`, combat refusals, the tab tap fallback, the module's real top, the resend waiting for a column: all checked).

### The user's rulings: Party mode turns OFF when you leave Home, and ANYTHING that takes the controller ends it

Second ruling (2026-10-28, answering the open question): the pause wheel, the bar cursor and a game window (vendor, loot ...) opened
in Party mode END it, as leaving Home does; nothing only suspends it any more.

- APP (the first word; party col 11 is a secure click, so it works in fights): `PartyModel.OFF_ON_LEAVE = true`.
  1. Leaving Home: `CompanionHost.show()` to a room, and `HomeView.apply()` going from the rest page to a module full screen
     (`onPartyLeaveRest`), call `partyLeaveHome(why)`: the party menu closes (MENU DONE when the pad opened it), then `off` is tapped
     whenever Party mode is ON (`offOnLeave`: suspended included; off is absolute).
  2. The pause wheel: `openPause()` on Home does the same before the wheel opens.
  3. ANY TAKER: every CURRENT `interact` document's `holder` (rooms, `pad:home`, `pad:radial` = the pause / consumables wheel,
     `pad:bar` = the bar cursor, `pad:class`, `npc` = an NPC / vendor / loot window that took the controller, in fights after the
     enter tap) -> `PartyModel.State.takerSeen` -> `off` tapped, once per holder, again after 2 s while Party mode still says on.
  Coming back to Home re-asks (col 22); the add-on's `party` (on false) and `partybox` (k off) have set the app's state already.
- ADD-ON: `PM.OFF_WHEN_ASIDE = true` back to "any aside": out of combat, any capture holder OR a game window (`windowOpen()`: the
  managed frames, a UI panel, the WINDOWS list incl. MerchantFrame / LootFrame) turns Party mode off (`PM.set(nil)`; the party
  document follows; the router binds today's plan when the taker lets go). At a fight's start (before the lockdown) too. Turning it
  ON while something holds is undone at once (the flip re-binds and finds the router aside).
- IN A FIGHT, exactly: secure attributes are locked, so the add-on never flips Party mode from insecure code there.
  - A room / window that takes the controller in the fight wins at once through room mode (the PRE edit: room mode first), so no
    press reaches the party pad while it holds.
  - The app sees the holder and taps party col 11: PARTYFLIP clears `wc-party` on the 8 router buttons and the pad (secure, no
    lockdown breach). The 8 keys stay carried to the router buttons for the rest of the fight (every fight's carry; bindings cannot
    change in the lockdown), and with `wc-party` gone they act as today's fight router (fight spells, give-back...). Select stays the
    party toggle for that fight (as before). At PLAYER_REGEN_ENABLED the router binds today's plan.
  - Backstop if the app's tap is lost: the add-on notes a taker during the fight (its 1 s tick and the capture's `onChange`: any
    holder, or a game window, while Party mode is on -> `PM.fightTaken`) and turns Party mode off at PLAYER_REGEN_ENABLED, even if
    the taker let go earlier. Until then (tap lost, taker gone mid-fight) the presses would reach the party pad again. A fight with no
    taker leaves Party mode on.
- Tests: add-on `test_party.py` group 4: off out of combat for `spells`, `pad:home`, `pad:radial`, `pad:bar`, MerchantFrame,
  LootFrame (all 8 refs + pad, party doc on false, nothing of ours bound, still off after the taker lets go); in a fight: (a) the
  app's col 11 tap = off at once, no breach, A still the fight router's; (b) no tap = still set in the fight, off at its end though the
  room let go first; (c) a loot window in the fight = off at its end; (d) no taker = stays on with its keys. App:
  `PartyFixturesTest.offWhenLeavingHome` and `takerEndsPartyMode`.

Third ruling (2026-11-11, the user, a healer: "Party mode needs to be usable in combat for healers. Locking it is retarded"; PadMap
m132). The second ruling now holds OUT OF COMBAT ONLY; in a fight nothing turns Party mode off except the player (Select, B, the tab):
- A fight's start with Party mode allowed never steps the router aside for a game window (a loot roll / popup at the pull): all 8 keys
  and Select are carried; Select is bound solo and with an unfocused invite too; while grouped (or on) the player's own m34 chords are
  not bound for that fight, so ZL + a face and the bare D-pad reach the party pad if it is turned on mid-fight (`PM.fightKeys`).
- In the fight: no taker is noted (`PM.noteFightTaker` / `PM.fightTaken` gone), nothing switches it off at PLAYER_REGEN_ENABLED; the
  app never taps `off` for a holder (`takerSeen(.., fight)`) or for leaving Home (`offOnLeave(.., fight)`); a room holding only
  suspends it (room mode first); the app draws it from `on` alone in a fight (`Party.onLive`) and the tab flips `on`.
- Out of combat a PROMPT (`PM.PROMPTS`: GroupLootFrame1, StaticPopup1-4, ReadyCheckFrame) only suspends it (A answers the prompt).
- Y in a fight never opens the player menu: the In combat action, else Smart Y's party buff armed before the fight (`wc-pb-<unit>`,
  partycfg's `sy.pick` now sent in fights too), else Target.
- Still refused in a fight (the game's lockdown): the Controller room's edits and Smart Y's party list (`partyack why=combat`).
- Tests: `test_party.py` "m132 fight-first" (the fight entered FIRST, then on / pick / move / casts / Touch / roster / pet / tab / B),
  "window-at-pull", the prompts; `PartyFixturesTest`, `PartyModelTest`, `PartyStripTest`.
- m133 (review of m132): the window-at-pull rule and the chords' release apply only while Party mode may be wanted in the fight
  (`PM.fightKeys`: on, grouped, or a pet out); solo with no pet and Party mode off, a game window open at the pull (GameMenuFrame,
  LootFrame, Gossip, a roll...) keeps its own controller navigation and the m34 chords stay, as before m132. Where the rule applies,
  EVERY game window open at the pull (not only a prompt) is answered by touch or after the fight. Not covered: an invite accepted
  or a pet summoned mid-fight after a solo, pet-less pull (the bindings were set at the pull). Smart Y's party pick casts once a fight
  per member (the secure Y clears `wc-pb-<unit>`; a roster change in the fight clears party1-4's; `PM.picksSync` drops them from
  partycfg, so the strip shows TARGET); the fight's end arms them again. PM.PROMPTS adds GamepadGroupLootRollFrame (the controller
  roll frame) and GroupLootFrame2-4; Interact's "dialogs" capture is a prompt (`PM.PROMPT_HOLDERS`, the app's
  `PartyModel.PROMPT_HOLDERS`). The tab's tap sends the opposite of what it shows (`PartyModel.tabWants` = `!onLive`).

### Cross-side fixtures (new)

`tools/test_party.py` (block "CROSS-SIDE FIXTURES") dumps 24 raw documents straight out of Safety.lua's encoder to
`dsforever/src/test/resources/party-fixtures/*.json` (party solo / three on / off; partycfg Shaman defaults, Warrior `"slots":[]`,
everything set with Smart Y picks; partybox on / move / cast / ZL none / menu / key Down / key B / off; partyack resend / cell /
touch-target / combat / slot / pb; padtabctl right page / party chooser / Y page on Add / pbuff chooser; pmenu party2). The harness
fails when the committed copies differ from what the Lua sends now (`PARTY_FIXTURES_WRITE=1` rewrites). `PartyFixturesTest.kt` (11
tests) feeds them to `PartyModel` parse + State, `PartyTaps`, `PadMapModel.parseCtl` and `PlayerMenu`.

### Results (exit codes)

| run | result |
|---|---|
| `python tools/test_party.py` | exit 0, ALL PASS (319 checks; was 274) |
| `python tools/test_smarty.py` (unchanged) | exit 0, ALL PASS (91) |
| `test_aggro` / `test_compass` / `test_hud_switches` / `test_interact` / `test_more_decode` / `test_prd` / `test_quests_focus` / `test_route_supertrack` / `test_tint` | all exit 0 (67 / 177 / 16 / 47 / 6 / 93 / 16 / 7 / 24 passes, 0 failed) |
| `gradlew --offline :dsforever:testDebugUnitTest` | exit 0, BUILD SUCCESSFUL: 788 tests, 0 failed (Party: Fixtures 11, Model 10, Layout 7, Strip 5, Taps 4, PlayerMenu 9) |
| `gradlew --offline :app:assembleModernDebug` | exit 0, BUILD SUCCESSFUL |
| PartyShotsTest (`PARTY_SHOTS_DIR=E:/wow-build/party-app/shots-int`, `PARTY_ART_ROOT=E:/wow-build/runtime-art/r1/files`) | exit 0; 17 PNGs in `E:/wow-build/party-app/shots-int` (looked at: Controller tab Y with Smart Y, Home n5 on, the player menu: as before, no regressions). No shot of the controller's `pbuff` chooser (JVM-tested only) |

### Still device-only (each needs the user's go and a party member)

FEASIBILITY P1 unit switch in a fight; P2 secret survey; P3 UNIT_COMBAT / UNIT_HEALTH on party1; P4 Select in a fight; P5 LB / RB bare
tap; P6 secure target cell in a fight; P7 secure togglemenu; P8 trigger chords reach the party pad; P9 menu read for a party unit;
P10 range. Plus the add-on worker's P11 (one tap on party cells 0, 10, 11, 22 at the Thor's UI scale: pad11 rows 54-55 land), the
fight-spell regression after the PRE edit (FEASIBILITY risk 4), and for this ruling: open a room and a Home module full screen with
Party mode on (in and out of a fight), and the pause wheel, the bar cursor, a vendor and a loot window (a loot window in a fight too);
come back, Party mode off and the keys the game's again.

## m134 / hub38: THE PLAYER / PET ROW, HIGHLIGHTING = TARGETING, THE SELECT TAB IN THE GAME'S ART (2026-11-11, after using 1428)

The user: "The size of the player and the pet don't feel right ... I think the pet should be to the right of the player and not have the
green dot. Also the a button glyph on them needs to be removed since highlighting it should target and a will be whatever spell is
bound", and "The side panel around the select button looks like an AI border. It needs to use wow assets and sit outside of the player
brand look more like its attached to the side of the screen" (party_design.md "PLAYER / PET REVISION", "SELECT TAB LOOK").

- Rows: the first row is you alone, or you + the pet on your right (`PartyLayout.PET_SHARE` 0.33: you about two thirds); the k party
  members under it by the adaptive rule among themselves (1-2 full rows; 3: a full row, then a shared one; 4: 2x2). One rule in
  `OV.partyNav(n, box, dir, pet)`, the PARTY-NAV snippet body and `PartyModel.rows / nav(n, box, dir, pet)`; `party_nav.json` now
  carries every shape with `pet` (no pet n 1..5, the pet n 2..6). Hub's top block (`PF.layout`) draws the party rows only, so it no
  longer depends on the pet.
- Highlighting = targeting: a PARTYPAD D-pad move that moved sets `type target` + `unit` (the new pick) in the same secure click, in
  fights too; an edge press targets nothing. PARTYPICK's pick of another plate targets it too; the picked plate's tap is the Touch
  spell, else Target again. A / X / Y and the ZL / ZR layers cast as before on the pick.
- Plates: no A / X / Y chips and no LB + button glyph (the "A" on your own plate was the game's LB + A targeting hint); the pet's plate
  has no stripe and no green dot, its portrait (`ic` = `GetPetIcon()`, plain only; icons.pak) and its short name.
- The Select tab: `UiKit.sideHandle` = the game's side tab (`side_tab`, lit by `side_tab_on`), cut in three down its height, flush on
  the screen's left edge (x 0..52); the member band (the well) starts at x 56. The pick's notch is the game's small gold arrow
  (`menu_next`). Hub: the same side tab atlas (`common-sidetab-c60-2x`, lit `common-sidetab-selected-c60-2x`) in three slices at x 0.
