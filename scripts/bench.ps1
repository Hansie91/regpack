# Usage: scripts\bench.ps1 <command> [options]   (run the bench command line; build first)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$java = if ($env:REGPACK_JAVA) { $env:REGPACK_JAVA } else { 'java' }
& $java -cp 'regpack-bench/target/classes;regpack-model/target/classes' dev.regpack.bench.cli.Bench @args
exit $LASTEXITCODE
