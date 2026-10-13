param([string]$Components = '', [switch]$SkipNative)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
foreach ($tool in @('git','python','java','bash','cmake','ninja')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Required on PATH: $tool" }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to your Android SDK' }
$ndk = Join-Path $env:ANDROID_HOME 'ndk/27.2.12479018'
if (-not (Test-Path (Join-Path $ndk 'build/cmake/android.toolchain.cmake'))) { throw 'Install Android NDK 27.2.12479018' }
$env:ANDROID_NDK = $ndk.Replace('\','/')
$env:PATH = (Join-Path $PSScriptRoot 'bin') + ';' + $env:PATH
$deps = Join-Path $root '.deps'
New-Item -ItemType Directory -Force -Path $deps | Out-Null
function Run([string]$command, [string[]]$arguments) {
    & $command @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$command failed ($LASTEXITCODE)" }
}
function Dependency([string]$name, [string]$url, [string]$revision) {
    $path = Join-Path $deps $name
    if (-not (Test-Path $path)) {
        Run 'git' @('init', $path)
        Run 'git' @('-C', $path, 'remote', 'add', 'origin', $url)
        Run 'git' @('-C', $path, 'fetch', '--depth=1', 'origin', $revision)
        Run 'git' @('-C', $path, 'checkout', '--detach', 'FETCH_HEAD')
    }
    $head = & git -C $path rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $head -ne $revision) { throw "Wrong $name revision" }
    if (& git -C $path status --porcelain) { throw "Dirty dependency: $name" }
    return $path.Replace('\','/')
}
Run 'bash' @((Join-Path $root 'tools/fetch_components.sh').Replace('\','/'), $Components)
if (-not $SkipNative) {
    $casc = Dependency 'CascLib' 'https://github.com/ladislav-zezula/CascLib.git' '38a34665624b8775bb875274b36191b21c38d97b'
    $recast = Dependency 'recastnavigation' 'https://github.com/recastnavigation/recastnavigation.git' '6dc1667f580357e8a2154c28b7867bea7e8ad3a7'
    Run 'bash' @((Join-Path $root 'companion/tools/harvest/build_native.sh').Replace('\','/'), $casc)
    Run 'bash' @((Join-Path $root 'companion/tools/interiors_native/build_android.sh').Replace('\','/'), $recast,
        (Join-Path $root '.bootstrap/interiors-android').Replace('\','/'))
}
Write-Output 'Bootstrap complete; run the Gradle wrapper from this checkout'
& (Join-Path $PSScriptRoot 'sync_notices.ps1')
