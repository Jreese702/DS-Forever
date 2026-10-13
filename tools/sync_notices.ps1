$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dest = Join-Path $root 'app/src/main/assets/notices'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
foreach ($name in @('LICENSE','THIRD_PARTY_NOTICES','RELEASE_NOTICES.md')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination (Join-Path $dest ($name + '.txt'))
}
$source = Join-Path $root 'licenses'
Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object Extension -ne '.json' | ForEach-Object {
    $relative = $_.FullName.Substring($source.Length + 1)
    $target = Join-Path (Join-Path $dest 'licenses') $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target
}
