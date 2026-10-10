param([Parameter(Mandatory = $true)][string]$Module, [Parameter(Mandatory = $true)][string]$Test,
      [Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$mvn = if ($env:REGPACK_MVN) { $env:REGPACK_MVN } else { '.\mvnw.cmd' }
& $mvn -B -ntp -pl $Module -am test "-Dtest=$Test" "-Dsurefire.failIfNoSpecifiedTests=false" @Extra
exit $LASTEXITCODE
