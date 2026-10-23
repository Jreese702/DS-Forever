; DS Forever Desktop installer (Inno Setup 6). Built by scripts/build_release.ps1 with /DAppVersion and /DStageDir.
;
; Per-user, no admin: the app, its bundled Sunshine (which writes next to itself) and all data live under the user's profile.
; What this installer deliberately does NOT do (each is a system change the user makes or approves themselves):
;  - no Windows Firewall rules: the first start of DsForeverDesktop.exe (bridge TCP 48210, mDNS UDP 5353) and of
;    sunshine\sunshine.exe (TCP 47984/47989/47990/48010 and UDP 47998-48000 at the default base; another base keeps the same
;    offsets) raises Windows' own "allow access" prompt; the user ticks Private networks;
;  - no Sunshine Windows service (Desktop runs Sunshine as its own child process);
;  - no ViGEmBus driver: Sunshine's virtual Xbox pad needs it (https://github.com/nefarius/ViGEmBus/releases); without it the
;    stream works and the controller doesn't;
;  - no Start with Windows entry: the user ticks it in the app (HKCU Run).

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef StageDir
  #define StageDir "..\dist\DsForeverDesktop"
#endif

[Setup]
AppId={{6B2A3F1E-6E0D-4C55-9C1B-5F0D2E8A7D31}
AppName=DS Forever Desktop
AppVersion={#AppVersion}
AppPublisher=DS Forever
AppPublisherURL=https://github.com/Jreese702/DS-Forever
DefaultDirName={localappdata}\Programs\DS Forever Desktop
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputBaseFilename=DsForeverDesktop-{#AppVersion}-setup
Compression=lzma2/max
SolidCompression=yes
LicenseFile={#StageDir}\LICENSE.txt
UninstallDisplayIcon={app}\DsForeverDesktop.exe
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.18362

[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{userprograms}\DS Forever Desktop"; Filename: "{app}\DsForeverDesktop.exe"

[Run]
Filename: "{app}\DsForeverDesktop.exe"; Description: "Start DS Forever Desktop"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C taskkill /IM DsForeverDesktop.exe /F"; Flags: runhidden; RunOnceId: "StopDesktop"

[UninstallDelete]
; Sunshine writes a config folder next to its exe
Type: filesandordirs; Name: "{app}\sunshine\config"

[Registry]
; removes the Start with Windows value the app may have set (the app itself only sets it when the user ticks the box)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "DS Forever Desktop"; Flags: uninsdeletevalue dontcreatekey
