# Windows packaging

The regpack distribution runs as a Windows service through Apache Commons Daemon procrun 1.6.1
(`prunsrv.exe`, JVM mode: the JVM runs inside the wrapper, a service stop calls
`dev.regpack.app.WindowsServiceEntry.stop` and is a graceful shutdown, not a process kill). All
scripts are ASCII only and run on Windows PowerShell 5.1 and on PowerShell 7. All of them need an
elevated PowerShell.

| Script | What it does |
| --- | --- |
| `install-procrun.ps1 -Dest <dir>` | Downloads `commons-daemon-1.6.1-bin-windows.zip` from the Apache distribution host (archive host as fallback), verifies the pinned SHA-512, expands it and prints the path of `amd64\prunsrv.exe`. The archive layout `amd64\prunsrv.exe` is checked at install time; a different layout stops the script. |
| `install-service.ps1 -Prunsrv <exe> -AppHome <dir>` | Registers the service `regpack` (parameters: `-JavaHome`, `-Port`, `-Bind`, `-Startup auto\|manual`, `-ServiceUser`), restricts the ACLs of the install directory and makes `logs` the only directory the service can write. |
| `smoke.ps1 -DistZip <zip> -Destructive` | Expands the distribution, installs procrun and the service, starts it, waits for `/health/ready`, runs `regpack smoke --health-url`, stops the service and asserts state `Stopped`, `ExitCode` 0 and the `regpack lifecycle: stopped cleanly` marker in the service log. |

The service runs as `NT AUTHORITY\LocalService` by default and never as LocalSystem. Only
Administrators and SYSTEM may write the install directory; only `logs` is writable by the service.
Install under `$env:ProgramFiles\regpack` for real use; `C:\regpack` is used only by the disposable
CI smoke. `smoke.ps1` deletes the service named `regpack` and the directory given by `-AppHome`, so
it needs `-Destructive` and belongs on a disposable machine (CI, a Windows VM or Windows Sandbox).

Uninstall: stop the service (`Stop-Service regpack`), remove it with
`prunsrv.exe //DS//regpack`, then delete the install directory.
