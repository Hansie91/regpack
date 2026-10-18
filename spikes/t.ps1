param([Parameter(Mandatory = $true)][string]$Module, [Parameter(Mandatory = $true)][string]$Test,
      [Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra)
# Refreshes the installed release modules the spikes use first (a stale install is the failure mode).
# Set SPIKES_SKIP_INSTALL to any value to skip the refresh.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$mvn = if ($env:REGPACK_MVN) { $env:REGPACK_MVN } else { '.\mvnw.cmd' }
if (-not $env:SPIKES_SKIP_INSTALL) {
  & $mvn -B -ntp -q -pl "regpack-bench,regpack-engine,regpack-inmemory" -am install -DskipTests "-Dspotless.check.skip=true"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& $mvn -B -ntp -f spikes/pom.xml -pl $Module -am test "-Dtest=$Test" "-Dsurefire.failIfNoSpecifiedTests=false" @Extra
exit $LASTEXITCODE
