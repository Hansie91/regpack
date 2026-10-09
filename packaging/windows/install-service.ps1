# Registers the regpack Windows service with procrun in jvm mode (the JVM runs inside prunsrv.exe).
# Usage: install-service.ps1 -Prunsrv <prunsrv.exe> -AppHome <install dir> [-JavaHome <JDK 25 dir>] [-Port 8080] [-Bind 127.0.0.1] [-Startup auto|manual] [-ServiceUser 'NT AUTHORITY\LocalService']
# The service never runs as LocalSystem. Only Administrators and SYSTEM may write the install directory; only logs is writable by the service.
param(
  [Parameter(Mandatory = $true)][string]$Prunsrv,
  [Parameter(Mandatory = $true)][string]$AppHome,
  [string]$JavaHome = $env:JAVA_HOME,
  [int]$Port = 8080,
  [string]$Bind = '127.0.0.1',
  [ValidateSet('auto', 'manual')][string]$Startup = 'auto',
  [string]$ServiceName = 'regpack',
  [string]$ServiceUser = 'NT AUTHORITY\LocalService'
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'JavaHome or JAVA_HOME must name a JDK 25' }
$jvm = Join-Path $JavaHome 'bin\server\jvm.dll'
if (-not (Test-Path $jvm)) { throw "jvm.dll not found: $jvm" }
$jar = Join-Path $AppHome 'lib\regpack-app.jar'
if (-not (Test-Path $jar)) { throw "regpack-app.jar not found: $jar" }
$logs = Join-Path $AppHome 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
# SIDs keep the script locale independent: S-1-5-32-544 Administrators, S-1-5-18 SYSTEM, S-1-5-19 LocalService
icacls $AppHome /inheritance:r /grant:r '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-18:(OI)(CI)F' '*S-1-5-19:(OI)(CI)RX' | Out-Null
icacls $logs /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
& $Prunsrv "//IS//$ServiceName" `
  "--DisplayName=regpack" "--Description=regpack regulatory reporting engine (walking skeleton)" `
  "--Install=$Prunsrv" "--Startup=$Startup" "--Jvm=$jvm" "--ServiceUser=$ServiceUser" `
  "--StartMode=jvm" "--StopMode=jvm" "--Classpath=$jar" `
  "--StartClass=dev.regpack.app.WindowsServiceEntry" "--StartMethod=start" "--StartParams=--bind;$Bind;--port;$Port" `
  "--StopClass=dev.regpack.app.WindowsServiceEntry" "--StopMethod=stop" "--StopTimeout=30" `
  "--LogPath=$logs" "--StdOutput=auto" "--StdError=auto" "--LogLevel=Info"
if ($LASTEXITCODE -ne 0) { throw "prunsrv install failed with exit code $LASTEXITCODE" }
