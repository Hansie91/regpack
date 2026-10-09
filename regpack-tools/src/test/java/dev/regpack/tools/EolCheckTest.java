package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

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
}
