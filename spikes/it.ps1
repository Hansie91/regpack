param([Parameter(Mandatory = $true)][string]$Module, [Parameter(Mandatory = $true)][string]$Test,
      [Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra)
# Refreshes the installed release modules the spikes use first. Set SPIKES_SKIP_INSTALL to any value to skip the refresh.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$mvn = if ($env:REGPACK_MVN) { $env:REGPACK_MVN } else { '.\mvnw.cmd' }
if (-not $env:SPIKES_SKIP_INSTALL) {
  & $mvn -B -ntp -q -pl "regpack-bench,regpack-engine,regpack-inmemory" -am install -DskipTests "-Dspotless.check.skip=true"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
# -Dspotless.check.skip=true: freshly hand-written code is not formatted yet; the full verify and CI still enforce the check
& $mvn -B -ntp -f spikes/pom.xml -pl $Module -am verify "-Dspotless.check.skip=true" "-Dit.test=$Test" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dtest=NoUnitTestsInThisRun" "-Dsurefire.failIfNoSpecifiedTests=false" @Extra
exit $LASTEXITCODE
