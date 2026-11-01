<#
.SYNOPSIS
  Fetches Google's platform-tools (scripts/platform-tools.lock), checks its SHA-256 and unpacks the three files DS Forever Desktop
  ships for the USB install: adb.exe, AdbWinApi.dll, AdbWinUsbApi.dll (plus NOTICE.txt and source.properties). Never committed.

.OUTPUTS
  The folder holding those files.
#>
param([string]$CacheDir = (Join-Path $PSScriptRoot "..\.cache"))
$ErrorActionPreference = "Stop"

$lock = @{}
Get-Content (Join-Path $PSScriptRoot "platform-tools.lock") | ForEach-Object {
    if ($_ -match '^\s*([a-z0-9]+)\s*=\s*(\S+)\s*$') { $lock[$Matches[1]] = $Matches[2] }
}
foreach ($k in "url", "sha256", "revision") { if (-not $lock[$k]) { throw "platform-tools.lock: '$k' missing" } }

New-Item -ItemType Directory -Force $CacheDir | Out-Null
$zip = Join-Path $CacheDir "platform-tools-latest-windows.zip"
function Good($f) { (Test-Path $f) -and ((Get-FileHash -Algorithm SHA256 $f).Hash.ToLowerInvariant() -eq $lock.sha256) }

if (-not (Good $zip)) {
    Write-Host "downloading $($lock.url)"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $lock.url -OutFile "$zip.part" -UseBasicParsing
    Move-Item -Force "$zip.part" $zip
    if (-not (Good $zip)) {
        throw "platform-tools SHA-256 mismatch: Google has published a newer 'latest'. Re-pin scripts/platform-tools.lock (revision + sha256)."
    }
}
Write-Host "verified platform-tools $($lock.revision) ($($lock.sha256))"

$out = Join-Path $CacheDir ("platform-tools-" + $lock.revision)
$want = "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "NOTICE.txt", "source.properties"
if (-not (Test-Path (Join-Path $out "adb.exe"))) {
    New-Item -ItemType Directory -Force $out | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        foreach ($e in $z.Entries) {
            if ($want -contains $e.Name -and $e.FullName -eq ("platform-tools/" + $e.Name)) {
                [IO.Compression.ZipFileExtensions]::ExtractToFile($e, (Join-Path $out $e.Name), $true)
            }
        }
    } finally { $z.Dispose() }
}
foreach ($f in $want) { if (-not (Test-Path (Join-Path $out $f))) { throw "$f missing from platform-tools" } }
$out
