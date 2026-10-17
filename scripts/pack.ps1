# Usage: scripts\pack.ps1 <pack command and options>   (run the regpack pack commands; build first)
# Relative paths are read from the repository root (the script changes to it, as scripts\bench.ps1 does).
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$java = if ($env:REGPACK_JAVA) { $env:REGPACK_JAVA } else { 'java' }
$modules = 'model', 'ports', 'pack', 'engine', 'inmemory', 'app'
$cp = ($modules | ForEach-Object { "regpack-$_/target/classes" }) -join ';'
& $java -cp $cp dev.regpack.app.Main pack @args
exit $LASTEXITCODE
