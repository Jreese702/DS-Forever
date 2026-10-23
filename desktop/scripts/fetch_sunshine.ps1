<#
.SYNOPSIS
  Fetches the pinned official Sunshine Windows release (scripts/sunshine.lock), checks its SHA-256 and unpacks it.
  Sunshine binaries are never committed to git; the release build runs this.

.PARAMETER CacheDir
  Where the zip is kept and unpacked (default: desktop/.cache, git-ignored). Nothing is downloaded when the verified zip is there.

.PARAMETER WithSource
  Also fetch the matching source archive (github.com/LizardByte/Sunshine/archive/refs/tags/<version>.zip) so the release can ship
  the corresponding source next to the GPL-3.0 binaries.

.OUTPUTS
  The path of the folder holding sunshine.exe.
#>
param(
    [string]$CacheDir = (Join-Path $PSScriptRoot "..\.cache"),
    [switch]$WithSource
)
$ErrorActionPreference = "Stop"

$lock = @{}
Get-Content (Join-Path $PSScriptRoot "sunshine.lock") | Where-Object { $_ -match '^\s*([a-z0-9]+)\s*=\s*(\S+)\s*$' } | ForEach-Object {
    $null = $_ -match '^\s*([a-z0-9]+)\s*=\s*(\S+)\s*$'
    $lock[$Matches[1]] = $Matches[2]
}
foreach ($k in "version", "asset", "sha256") { if (-not $lock[$k]) { throw "sunshine.lock: '$k' missing" } }

$base = "https://github.com/LizardByte/Sunshine/releases/download/$($lock.version)/$($lock.asset)"
New-Item -ItemType Directory -Force $CacheDir | Out-Null
$zip = Join-Path $CacheDir $lock.asset
$out = Join-Path $CacheDir ("sunshine-" + $lock.version)

function Test-Hash($file, $want) {
    if (-not (Test-Path $file)) { return $false }
    return ((Get-FileHash -Algorithm SHA256 $file).Hash.ToLowerInvariant() -eq $want.ToLowerInvariant())
}

if (-not (Test-Hash $zip $lock.sha256)) {
    Write-Host "downloading $base"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $base -OutFile "$zip.part" -UseBasicParsing
    Move-Item -Force "$zip.part" $zip
    if (-not (Test-Hash $zip $lock.sha256)) {
        Remove-Item -Force $zip
        throw "SHA-256 mismatch for $($lock.asset): not the pinned Sunshine release"
    }
}
Write-Host "verified $($lock.asset) ($($lock.sha256))"

$exe = Join-Path $out "Sunshine\sunshine.exe"
if (-not (Test-Path $exe)) {
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    Expand-Archive -Path $zip -DestinationPath $out
}
if (-not (Test-Path $exe)) { throw "sunshine.exe not found in $out" }

if ($WithSource) {
    $src = Join-Path $CacheDir ("Sunshine-" + $lock.version + "-source.zip")
    if (-not (Test-Path $src)) {
        $url = "https://github.com/LizardByte/Sunshine/archive/refs/tags/$($lock.version).zip"
        Write-Host "downloading the corresponding source $url"
        Invoke-WebRequest -Uri $url -OutFile "$src.part" -UseBasicParsing
        Move-Item -Force "$src.part" $src
    }
    Write-Host "source: $src"
}

Split-Path $exe
