# DS Forever Desktop

The Windows half of DS Forever's Streaming mode (STREAMING_PLAN.md, workstream S3): it lets the DS Forever app on an AYN Thor
stream WoW Forever from this PC. One tray app that

- runs a bundled, headless **Sunshine** (its own config in `%LOCALAPPDATA%\DSForeverDesktop\sunshine`, tray and web UI off, encoder
  auto-detected, HEVC advertised when the GPU has it, one app "WoW Forever"), as a child process, never a service;
- shows **"Pair a device: 123 456"**; the Thor proves the code (HMAC), gets a bridge token and a one-time Moonlight PIN, and Desktop
  hands that PIN to Sunshine's local API, so Moonlight pairs with no web page;
- runs the **bridge**: follows WoW's `Logs/General.log` (`_classic_beta_` until 2026-11-04, `_forever_` from then, or whichever log
  is being written), reassembles the add-on's WCB1 documents and streams them over a token-protected WebSocket (ws and wss on one
  port, 48210), advertised as `_dsforever._tcp` by mDNS. Protocol: [docs/STREAM_BRIDGE_PROTOCOL.md](docs/STREAM_BRIDGE_PROTOCOL.md);
- installs and updates DS Forever's add-ons in the PC game's `Interface/AddOns` from the APK's own set (`manifest.txt`), asking for
  the WoW folder once when it can't find it. Nothing else in the game folder is written: no macros, bindings or CVars;
- brings the WoW window to the front when a stream starts; Start with Windows (HKCU Run, only when ticked).

## Build

Needs a .NET SDK (6 or newer) and the .NET Framework 4.8 targeting pack; the app runs on the .NET Framework 4.8 that ships with
Windows 10 1903+ / 11, so it needs no runtime of its own. No NuGet packages.

```
dotnet build desktop/src/DsfDesktop/DsfDesktop.csproj
dotnet build desktop/tests/DsfDesktop.Tests/DsfDesktop.Tests.csproj
desktop/tests/DsfDesktop.Tests/bin/Debug/DsfDesktop.Tests.exe                      # unit tests
desktop/tests/DsfDesktop.Tests/bin/Debug/DsfDesktop.Tests.exe --sunshine <dir>     # + the real Sunshine (127.0.0.1 only)
desktop/tests/DsfDesktop.Tests/bin/Debug/DsfDesktop.Tests.exe --serve --sunshine <dir>   # a live bridge + fake game for /test
powershell -File desktop/scripts/build_release.ps1                                 # release: tests, pinned Sunshine, zip, installer
```

Sunshine is never committed: `scripts/fetch_sunshine.ps1` downloads the release pinned in `scripts/sunshine.lock` and checks its
SHA-256. `installer/DsForeverDesktop.iss` (Inno Setup 6) makes a per-user installer from the staged folder.

Development switches: `DSF_LOOPBACK=1` (bridge and Sunshine on 127.0.0.1, no mDNS: no firewall prompt), `DSF_DESKTOP_DATA`,
`DSF_SUNSHINE_DIR`, `DSF_ADDON_SOURCE`. The bridge's test page is `http://localhost:48210/test` (this PC only).

## Licence

GPL-3.0-or-later, as DS Forever (`../LICENSE`). Bundled third-party software: [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
