<#
.SYNOPSIS
  Builds DS Forever Desktop for release: compiles, runs the tests, fetches the pinned Sunshine, stages the app folder, zips it,
  and compiles the installer when Inno Setup (ISCC.exe) is installed.

.PARAMETER Dotnet
  The dotnet executable (any .NET SDK 6+ with the .NET Framework 4.8 targeting pack). Default: dotnet on PATH.

.PARAMETER LiveSunshine
  Also run the tests that start the real Sunshine (on 127.0.0.1, a spare port, nothing on the LAN).

.PARAMETER WithSource
  Fetch Sunshine's corresponding source too and put it in the dist folder (GPL-3.0: ship or offer the source with the binaries).
#>
param(
    [string]$Dotnet = "dotnet",
    [string]$CacheDir = (Join-Path $PSScriptRoot "..\.cache"),
    [switch]$LiveSunshine,
    [switch]$WithSource
)
$ErrorActionPreference = "Stop"
$desktop = Resolve-Path (Join-Path $PSScriptRoot "..")
$repo = Resolve-Path (Join-Path $desktop "..")

& $Dotnet build (Join-Path $desktop "tests\DsfDesktop.Tests\DsfDesktop.Tests.csproj") -c Release -nologo -v q
if ($LASTEXITCODE -ne 0) { throw "test build failed" }
& $Dotnet build (Join-Path $desktop "src\DsfDesktop\DsfDesktop.csproj") -c Release -nologo -v q
if ($LASTEXITCODE -ne 0) { throw "app build failed" }

$sunshineDir = & (Join-Path $PSScriptRoot "fetch_sunshine.ps1") -CacheDir $CacheDir -WithSource:$WithSource | Select-Object -Last 1
$adbDir = & (Join-Path $PSScriptRoot "fetch_platform_tools.ps1") -CacheDir $CacheDir | Select-Object -Last 1

$testExe = Join-Path $desktop "tests\DsfDesktop.Tests\bin\Release\DsfDesktop.Tests.exe"
if ($LiveSunshine) { & $testExe --sunshine $sunshineDir } else { & $testExe }
if ($LASTEXITCODE -ne 0) { throw "$LASTEXITCODE test(s) failed" }

$version = (Get-Item (Join-Path $desktop "src\DsfDesktop\bin\Release\DsForeverDesktop.exe")).VersionInfo.ProductVersion
$dist = Join-Path $desktop "dist"
$final = Join-Path $dist "DsForeverDesktop"
# staged beside the old folder, never over it: Desktop (and its Sunshine) may be running from dist\DsForeverDesktop, and deleting
# it under them half-empties a live install
$stage = Join-Path $dist "DsForeverDesktop.new"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null

Copy-Item (Join-Path $desktop "src\DsfDesktop\bin\Release\DsForeverDesktop.exe"), `
          (Join-Path $desktop "src\DsfDesktop\bin\Release\DsForeverDesktop.exe.config"), `
          (Join-Path $desktop "src\DsfDesktop\bin\Release\DsfDesktop.Core.dll") $stage
Copy-Item -Recurse $sunshineDir (Join-Path $stage "sunshine")
# adb for the Thor's USB install (only the three files it needs, with Google's notices)
Copy-Item -Recurse $adbDir (Join-Path $stage "platform-tools")
# the same add-on set the APK ships
$companion = Join-Path $repo "companion\app\src\main\assets\companion"
New-Item -ItemType Directory -Force (Join-Path $stage "companion") | Out-Null
Copy-Item (Join-Path $companion "manifest.txt") (Join-Path $stage "companion")
Copy-Item -Recurse (Join-Path $companion "addons") (Join-Path $stage "companion\addons")
Copy-Item (Join-Path $repo "LICENSE") (Join-Path $stage "LICENSE.txt")
Copy-Item (Join-Path $desktop "THIRD_PARTY_NOTICES") (Join-Path $stage "THIRD_PARTY_NOTICES.txt")
if ($WithSource) { Copy-Item (Get-ChildItem $CacheDir -Filter "Sunshine-*-source.zip" | Select-Object -First 1).FullName $stage }

$zip = Join-Path $dist ("DsForeverDesktop-" + $version + "-win-x64.zip")
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
Write-Host "zip: $zip"

# swap the new folder in, only when nothing runs from the old one (a folder with running programs in it can't be renamed)
$old = Join-Path $dist "DsForeverDesktop.old"
if (Test-Path $old) { Remove-Item -Recurse -Force $old -ErrorAction SilentlyContinue }
$swapped = $true
if (Test-Path $final) {
    try { Rename-Item $final "DsForeverDesktop.old" -ErrorAction Stop } catch { $swapped = $false }
}
if ($swapped) {
    Rename-Item $stage "DsForeverDesktop"
    $stage = $final
    if (Test-Path $old) { Remove-Item -Recurse -Force $old -ErrorAction SilentlyContinue }
    Write-Host "app folder: $final"
} else {
    Write-Host "Desktop is running from $final, which was left alone. The new build is in ${stage}:"
    Write-Host "quit Desktop (tray > Quit), then replace $final with it."
}

$iscc = @("$env:ProgramFiles(x86)\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe", "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if ($iscc) {
    & $iscc "/DAppVersion=$version" "/DStageDir=$stage" "/O$dist" (Join-Path $desktop "installer\DsForeverDesktop.iss")
    if ($LASTEXITCODE -ne 0) { throw "installer build failed" }
} else {
    Write-Host "Inno Setup not installed: the zip is the release (installer/DsForeverDesktop.iss builds the installer)"
}
