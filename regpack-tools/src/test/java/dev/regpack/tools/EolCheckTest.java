package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EolCheckTest {
  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void crInShellScriptFails() {
    List<String> problems = EolCheck.checkBytes("a.sh", ascii("#!/bin/sh\r\necho hi\r\n"));
    assertThat(problems).containsExactly("a.sh: CR_IN_LF_FILE");
  }

  @Test
  void bomFails() {
    byte[] content = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\n'};
    assertThat(EolCheck.checkBytes("a.txt", content)).containsExactly("a.txt: BOM");
  }

  @Test
  void cmdWithBareLfFails() {
    assertThat(EolCheck.checkBytes("a.cmd", ascii("@echo off\r\necho hi\n")))
        .containsExactly("a.cmd: LF_IN_CMD");
  }

  @Test
  void cmdWithCrlfPasses() {
    assertThat(EolCheck.checkBytes("a.cmd", ascii("@echo off\r\necho hi\r\n"))).isEmpty();
  }

  @Test
  void nonAsciiInPowerShellFails() {
    assertThat(EolCheck.checkBytes("a.ps1", ascii("Write-Host 'caf\u00e9'\n")))
        .containsExactly("a.ps1: NON_ASCII");
  }

  @Test
  void shellScriptWithoutShebangFails() {
    assertThat(EolCheck.checkBytes("a.sh", ascii("echo hi\n"))).containsExactly("a.sh: NO_SHEBANG");
  }

  @Test
  void lfJavaFilePasses() {
    assertThat(EolCheck.checkBytes("src/A.java", ascii("class A {}\n// caf\u00e9\n"))).isEmpty();
  }

  @Test
  void binaryAndUnknownExtensionsAreIgnored() {
    byte[] bytes = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '\r', '\n', 0, (byte) 0xFF};
    assertThat(EolCheck.checkBytes("a.jar", bytes)).isEmpty();
    assertThat(EolCheck.checkBytes("data.bin", bytes)).isEmpty();
  }

  @Test
  void crlfDockerfileAndMvnwFail() {
    assertThat(EolCheck.checkBytes("packaging/Dockerfile", ascii("FROM scratch\r\n")))
        .containsExactly("packaging/Dockerfile: CR_IN_LF_FILE");
    assertThat(EolCheck.checkBytes("mvnw", ascii("#!/bin/sh\r\nexec true\r\n")))
        .containsExactly("mvnw: CR_IN_LF_FILE");
  }

  @Test
  void lfDockerfileAndMvnwWithShebangPass() {
    assertThat(EolCheck.checkBytes("Dockerfile", ascii("FROM scratch\n"))).isEmpty();
    assertThat(EolCheck.checkBytes("mvnw", ascii("#!/bin/sh\nexec true\n"))).isEmpty();
  }

  @Test
  void mvnwWithoutShebangFails() {
    assertThat(EolCheck.checkBytes("mvnw", ascii("exec true\n")))
        .containsExactly("mvnw: NO_SHEBANG");
  }

  @Test
  void nonAsciiServiceUnitFails() {
    assertThat(EolCheck.checkBytes("regpack.service", ascii("[Unit]\nDescription=café\n")))
        .containsExactly("regpack.service: NON_ASCII");
    assertThat(EolCheck.checkBytes("regpack.service", ascii("[Unit]\nDescription=cafe\n")))
        .isEmpty();
  }

  @Test
  void crInPropertiesAndYamlFails() {
    assertThat(EolCheck.checkBytes("app.properties", ascii("a=b\r\n")))
        .containsExactly("app.properties: CR_IN_LF_FILE");
    assertThat(EolCheck.checkBytes("ci.yml", ascii("a: b\r\n")))
        .containsExactly("ci.yml: CR_IN_LF_FILE");
  }

  @Test
  void checkReadsWorkingTreeFilesAndSkipsMissingOnes(@TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("scripts"));
    Files.write(root.resolve("scripts/bad.sh"), ascii("#!/bin/sh\r\n"));
    Files.write(root.resolve("scripts/good.sh"), ascii("#!/bin/sh\n"));
    Files.write(root.resolve("app.properties"), ascii("a=b\r\n"));
    Files.write(root.resolve("win.cmd"), ascii("@echo off\n"));
    Files.write(root.resolve("data.bin"), new byte[] {'\r', (byte) 0xFF});
    List<String> problems =
        EolCheck.check(
            root,
            List.of(
                "scripts/bad.sh",
                "scripts/good.sh",
                "gone.sh",
                "scripts",
                "app.properties",
                "win.cmd",
                "data.bin"));
    assertThat(problems)
        .containsExactly(
            "scripts/bad.sh: CR_IN_LF_FILE", "app.properties: CR_IN_LF_FILE", "win.cmd: LF_IN_CMD");
  }
}
