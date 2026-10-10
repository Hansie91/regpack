param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$image = 'maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
$repo = Split-Path $PSScriptRoot -Parent
& docker run --rm -v "${repo}:/work" -w /work -v regpack-m2:/root/.m2 $image mvn @MavenArgs
exit $LASTEXITCODE
