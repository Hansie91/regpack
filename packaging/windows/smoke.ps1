# Smoke run of the regpack distribution as a Windows service (plan P0; spec 5.2 and 14.5).
# Run from an elevated PowerShell with JAVA_HOME pointing at a JDK 25.
# Usage: smoke.ps1 -DistZip <regpack-*-dist.zip> -Destructive [-AppHome C:\regpack] [-Port 18080]
# Destructive: removes the service named regpack and the directory AppHome. Use a disposable machine (CI, a Windows VM or Windows Sandbox).
param(
  [Parameter(Mandatory = $true)][string]$DistZip,
  [string]$AppHome = 'C:\regpack',
  [int]$Port = 18080,
  [switch]$Destructive
)
$ErrorActionPreference = 'Stop'
$name = 'regpack'
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME must point at a JDK 25' }
if (-not $Destructive) { throw 'smoke.ps1 deletes the service named regpack and the directory given by -AppHome; rerun with -Destructive on a disposable machine' }

function Remove-RegpackService([string]$prunsrv) {
  $existing = Get-CimInstance -ClassName Win32_Service -Filter "Name='$name'" -ErrorAction SilentlyContinue
  if ($existing -and ($existing.PathName -notlike "*$AppHome*")) {
    throw "a service named $name exists with path $($existing.PathName), which is not under $AppHome; refusing to remove it"
  }
  if (Get-Service -Name $name -ErrorAction SilentlyContinue) {
    Stop-Service -Name $name -Force -ErrorAction SilentlyContinue
    if ($prunsrv -and (Test-Path $prunsrv)) { & $prunsrv "//DS//$name" | Out-Null }
  }
}

$prunsrvInstalled = Join-Path $AppHome 'bin\prunsrv.exe'
Remove-RegpackService $prunsrvInstalled
if (Test-Path $AppHome) { Remove-Item $AppHome -Recurse -Force }
$extract = Join-Path ([System.IO.Path]::GetTempPath()) 'regpack-dist-extract'
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
Expand-Archive -Path $DistZip -DestinationPath $extract -Force
$inner = Get-ChildItem -Path $extract -Directory | Select-Object -First 1
Move-Item -Path $inner.FullName -Destination $AppHome

$scripts = Join-Path $AppHome 'share\windows'
$dl = Join-Path $AppHome 'procrun'
$prunsrv = (& (Join-Path $scripts 'install-procrun.ps1') -Dest $dl | Select-Object -Last 1)
Copy-Item -Path $prunsrv -Destination $prunsrvInstalled -Force
& (Join-Path $scripts 'install-service.ps1') -Prunsrv $prunsrvInstalled -AppHome $AppHome -Port $Port -Startup manual

try {
  Start-Service -Name $name
  $ready = $false
  for ($i = 0; $i -lt 60; $i++) {
    try {
      $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health/ready" -UseBasicParsing -TimeoutSec 2
      if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
  }
  if (-not $ready) { throw 'service not ready after 60 s' }

  & (Join-Path $AppHome 'bin\regpack.cmd') smoke --health-url "http://127.0.0.1:$Port"
  if ($LASTEXITCODE -ne 0) { throw "regpack smoke failed with exit code $LASTEXITCODE" }

  Stop-Service -Name $name
  $svc = Get-CimInstance -ClassName Win32_Service -Filter "Name='$name'"
  if ($svc.State -ne 'Stopped') { throw "service state is $($svc.State), expected Stopped" }
  if ($svc.ExitCode -ne 0) { throw "service ExitCode is $($svc.ExitCode), expected 0" }
  # The marker must be in a log written by the service itself. Every *.log in the service log folder
  # counts, because procrun's stdout file name is not fixed by anything we control.
  $logs = Join-Path $AppHome 'logs'
  $logFiles = @(Get-ChildItem -Path $logs -Filter '*.log' -ErrorAction SilentlyContinue)
  $marker = $logFiles | Get-Content -ErrorAction SilentlyContinue |
    Select-String -SimpleMatch 'regpack lifecycle: stopped cleanly'
  if (-not $marker) {
    Write-Host '--- smoke diagnostics: service log folder'
    icacls $logs | Write-Host
    Get-ChildItem -Path $logs -Force | Format-Table Name, Length, LastWriteTime | Out-String | Write-Host
    foreach ($f in $logFiles) {
      Write-Host "--- last 40 lines of $($f.Name)"
      Get-Content -Path $f.FullName -Tail 40 | Write-Host
    }
    throw 'no clean-stop marker in the service log'
  }
  Write-Host 'smoke: windows service OK'
}
finally {
  Remove-RegpackService $prunsrvInstalled
}
