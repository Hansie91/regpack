package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Static checks of the packaging files under {@code packaging/}; no service is installed. */
class PackagingFilesTest {
  private static Path repoRoot() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String asciiLfText(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    for (byte b : bytes) {
      assertThat(b >= 0).as(file + " is ASCII").isTrue();
      assertThat(b).as(file + " has no CR").isNotEqualTo((byte) '\r');
    }
    try {
      return StandardCharsets.US_ASCII
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException(file + " is not ASCII", e);
    }
  }

  @Test
  void systemdUnitHasHardeningAndCleanStopSettings() throws IOException {
    Path unit = repoRoot().resolve("packaging/systemd/regpack.service");
    String text = asciiLfText(unit);
    List<String> lines = List.of(text.split("\n", -1));

    assertThat(lines)
        .contains(
            "Type=simple",
            "DynamicUser=yes",
            "StateDirectory=regpack",
            "EnvironmentFile=-/etc/regpack/regpack.env",
            "ExecStart=/opt/regpack/bin/regpack serve",
            "SuccessExitStatus=143",
            "NoNewPrivileges=yes",
            "ProtectSystem=strict",
            "ProtectHome=yes",
            "PrivateTmp=yes",
            "RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX",
            "CapabilityBoundingSet=",
            "Restart=on-failure");
    assertThat(lines).noneMatch(l -> l.startsWith("User=") || l.startsWith("Group="));

    String smoke = asciiLfText(repoRoot().resolve("packaging/systemd/smoke.sh"));
    assertThat(smoke).contains("--no-same-owner").contains("--destructive");
  }

  @Test
  void windowsScriptsPinProcrunChecksumAndAreAscii() throws IOException {
    Path dir = repoRoot().resolve("packaging/windows");
    String procrun = asciiLfText(dir.resolve("install-procrun.ps1"));
    String service = asciiLfText(dir.resolve("install-service.ps1"));
    String smoke = asciiLfText(dir.resolve("smoke.ps1"));

    assertThat(procrun)
        .contains(
            "a878177edc92e663c1113858f8903451ec02b5b033bca373bea4f8761d9196323771301fcd6a7b8387d8e2300f7a1056b9e08e83fca9703df32ac3ecd5b129f7")
        .contains("Get-FileHash -Algorithm SHA512")
        .contains("commons-daemon-1.6.1-bin-windows.zip");
    assertThat(service)
        .contains(
            "--StartMode=jvm",
            "--StopMode=jvm",
            "dev.regpack.app.WindowsServiceEntry",
            "--StartMethod=start",
            "--StopMethod=stop",
            "bin\\server\\jvm.dll",
            "--ServiceUser",
            "icacls");
    assertThat(smoke).contains("stopped cleanly", "ExitCode", "-Destructive");
  }
}
