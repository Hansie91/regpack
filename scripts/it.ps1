param([Parameter(Mandatory = $true)][string]$Module, [Parameter(Mandatory = $true)][string]$Test,
      [Parameter(ValueFromRemainingArguments = $true)][string[]]$Extra)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$mvn = if ($env:REGPACK_MVN) { $env:REGPACK_MVN } else { '.\mvnw.cmd' }
# -Dspotless.check.skip=true: freshly hand-written code is not formatted yet; the full verify and CI still enforce the check
& $mvn -B -ntp -pl $Module -am verify "-Dspotless.check.skip=true" "-Dit.test=$Test" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dtest=NoUnitTestsInThisRun" "-Dsurefire.failIfNoSpecifiedTests=false" @Extra
exit $LASTEXITCODE
