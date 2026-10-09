# Toolchain for regpack

Java 25 (Temurin) and Maven 3.9.16 through the Maven Wrapper. Nothing here is installed system-wide
by the build; the agent and the scripts use a per-shell `JAVA_HOME`.

## Path A: local Temurin 25 (used on the owner machine)

JDK: Eclipse Adoptium release `jdk-25.0.4.1+1`

- URL: https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip
- Size: 141,167,264 bytes
- SHA-256: `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`
  (the same value is published by the Adoptium API and in the release asset
  `OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip.sha256.txt`)
- Optional: verify the detached `.sig` next to the zip with `gpg --verify`, importing Adoptium's key from
  https://adoptium.net/installation/ and comparing the fingerprint printed there.

Maven (bootstrap only, to generate the wrapper): Apache Maven 3.9.16

- URL: https://downloads.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip
  (if superseded: https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/)
- Size: 9,395,475 bytes
- SHA-512: `ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3`
- SHA-256 (pinned in `.mvn/wrapper/maven-wrapper.properties` as `distributionSha256Sum`):
  `5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`

Layout on the owner machine: `C:\dev\dl` (downloads), `C:\dev\jdk\jdk-25.0.4.1+1` (JDK),
`C:\dev\tools\apache-maven-3.9.16` (bootstrap Maven), `C:\dev\regpack` (working copy).

### Per-shell environment

Git Bash:

```bash
export JAVA_HOME=/c/dev/jdk/jdk-25.0.4.1+1; export PATH="$JAVA_HOME/bin:$PATH"
```

PowerShell:

```powershell
$env:JAVA_HOME='C:\dev\jdk\jdk-25.0.4.1+1'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
```

To make it permanent, set the user environment variables `JAVA_HOME` and `Path` yourself
(Settings, System, About, Advanced system settings, Environment Variables). The build does not do this.

### Verify

`./mvnw -v` (PowerShell: `.\mvnw.cmd -v`) must print `Apache Maven 3.9.16` and
`Java version: 25.0.4.1, vendor: Eclipse Adoptium`.

Tamper check: change one hex digit of `distributionSha256Sum`, delete
`%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.16` (the wrapper names the cache folder without a `-bin`
suffix), run `./mvnw -v` again. Expected: `Failed to validate Maven distribution SHA-256`. Restore the value.

PowerShell note: quote any Maven argument that contains `=`, `,` or `#` and a dot, for example
`"-Dmaven=3.9.16"`; unquoted, PowerShell 5.1 splits it.

## Path B: Docker fallback (not used on the owner machine)

`toolchain/docker-mvn.sh` / `toolchain/docker-mvn.ps1` run Maven in the digest-pinned image
`maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976`;
`toolchain/docker-java.sh` runs `java` from the same image for the gates. Set `REGPACK_MVN=toolchain/docker-mvn.sh`
and `REGPACK_JAVA=toolchain/docker-java.sh` so the `scripts/*.sh` helpers use the containers. Limits: no
`REGPACK_BUILD_ROOT` support (the container does not receive it), so the working copy must be outside OneDrive;
the gates need Git Bash. The first run pulls the image (needs the owner's approval).

## Where the working copy lives

The working copy is `C:\dev\regpack`, outside OneDrive (owner decision, 2026-10-09). Maven writes thousands of
small files to `target/`, and OneDrive's sync client can hold files open so that `clean` or git operations fail
with `index.lock` errors. A stale copy of the documentation remains in the OneDrive folder `ProjectRere`; treat it
as a backup, not as the working copy. Do not rely on junctions or symlinks into OneDrive: Microsoft documents that
OneDrive does not sync through them. If a working copy ever stays in OneDrive, set `REGPACK_BUILD_ROOT` (for
example `C:\dev\regpack-build`) before running Maven; it does not protect `.git`, `.mvn/wrapper` or the sources.
