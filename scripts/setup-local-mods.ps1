$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mods = Join-Path $root 'run\mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Copy-Item (Join-Path $root 'local-mods\*.jar') $mods -Force
Write-Host 'Copied supplied Forge mods to run/mods.'
Write-Host 'Add TaCZ Forge 1.20.1, Curios 5.14+, GeckoLib 4.8.3+, and Xaero World Map Forge 1.20.1 manually.'
