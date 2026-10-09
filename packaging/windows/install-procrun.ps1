# Downloads Apache Commons Daemon procrun 1.6.1 (commons-daemon-1.6.1-bin-windows.zip) and verifies its SHA-512 before using it.
# Usage: install-procrun.ps1 -Dest <directory>      Prints the full path of amd64\prunsrv.exe.
param([Parameter(Mandatory = $true)][string]$Dest)
$ErrorActionPreference = 'Stop'
$version = '1.6.1'
$sha512 = 'a878177edc92e663c1113858f8903451ec02b5b033bca373bea4f8761d9196323771301fcd6a7b8387d8e2300f7a1056b9e08e83fca9703df32ac3ecd5b129f7'
$name = "commons-daemon-$version-bin-windows.zip"
$urls = @(
  "https://downloads.apache.org/commons/daemon/binaries/windows/$name",
  "https://archive.apache.org/dist/commons/daemon/binaries/windows/$name"
)
$zip = Join-Path ([System.IO.Path]::GetTempPath()) $name
if (Test-Path $zip) { Remove-Item $zip -Force }
foreach ($u in $urls) {
  try { Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing; break }
  catch { Write-Host "download failed from $u" }
}
if (-not (Test-Path $zip)) { throw 'procrun download failed from every host' }
$actual = (Get-FileHash -Algorithm SHA512 -Path $zip).Hash.ToLowerInvariant()
if ($actual -ne $sha512) { Remove-Item $zip -Force; throw "SHA-512 mismatch for ${name}: expected $sha512 got $actual" }
New-Item -ItemType Directory -Force $Dest | Out-Null
Expand-Archive -Path $zip -DestinationPath $Dest -Force
$prunsrv = Get-ChildItem -Path $Dest -Recurse -Filter 'prunsrv.exe' |
  Where-Object { $_.Directory.Name -ieq 'amd64' } | Select-Object -First 1
if (-not $prunsrv) {
  Get-ChildItem -Recurse $Dest | Select-Object -First 40 | ForEach-Object { Write-Host $_.FullName }
  throw 'amd64\prunsrv.exe not found in the archive (the archive layout differs from the documented one; stop and ask the owner)'
}
Write-Output $prunsrv.FullName
