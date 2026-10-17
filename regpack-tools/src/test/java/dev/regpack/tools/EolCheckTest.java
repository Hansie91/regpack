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

  /** A text with one code point in the middle; the code point is built, never written raw. */
  private static byte[] withCodePoint(int codePoint) {
    String text = "line one\nline " + new String(Character.toChars(codePoint)) + " two\n";
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void bidiControlsAreRefusedInEveryCheckedTextType() {
    int[] bidi = {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069};
    for (int codePoint : bidi) {
      assertThat(EolCheck.checkBytes("A.java", withCodePoint(codePoint)))
          .as("U+%04X", codePoint)
          .containsExactly("A.java: BIDI_CONTROL (line 2)");
    }
    for (String name :
        List.of(
            "a.java",
            "a.yaml",
            "a.yml",
            "a.md",
            "a.tsv",
            "a.csv",
            "a.sh",
            "a.ps1",
            "a.json",
            "a.xml",
            "a.properties")) {
      byte[] content = ascii("#!/bin/sh\n");
      byte[] bad = withCodePoint(0x202E);
      byte[] both = new byte[content.length + bad.length];
      System.arraycopy(content, 0, both, 0, content.length);
      System.arraycopy(bad, 0, both, content.length, bad.length);
      assertThat(EolCheck.checkBytes(name, both))
          .as(name)
          .anyMatch(problem -> problem.startsWith(name + ": BIDI_CONTROL"));
    }
  }

  @Test
  void zeroWidthCharactersAreRefused() {
    int[] zeroWidth = {0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2060};
    for (int codePoint : zeroWidth) {
      assertThat(EolCheck.checkBytes("A.java", withCodePoint(codePoint)))
          .as("U+%04X", codePoint)
          .containsExactly("A.java: ZERO_WIDTH (line 2)");
    }
  }

  @Test
  void aByteOrderMarkInsideATextIsRefusedAndALeadingOneIsReportedOnce() {
    assertThat(EolCheck.checkBytes("A.java", withCodePoint(0xFEFF)))
        .containsExactly("A.java: ZERO_WIDTH (line 2)");
    byte[] leading =
        (new String(Character.toChars(0xFEFF)) + "class A {}\n").getBytes(StandardCharsets.UTF_8);
    assertThat(EolCheck.checkBytes("A.java", leading)).containsExactly("A.java: BOM");
    assertThat(EolCheck.checkBytes("data.csv", leading))
        .containsExactly("data.csv: ZERO_WIDTH (line 1)");
  }

  @Test
  void unicodeLineSeparatorsAreRefused() {
    for (int codePoint : new int[] {0x2028, 0x2029, 0x0085}) {
      assertThat(EolCheck.checkBytes("A.java", withCodePoint(codePoint)))
          .as("U+%04X", codePoint)
          .containsExactly("A.java: UNICODE_LINE_SEPARATOR (line 2)");
    }
  }

  @Test
  void eachProblemIsReportedOncePerFileAtItsFirstLine() {
    String text =
        "a\n"
            + new String(Character.toChars(0x200B))
            + "\nb "
            + new String(Character.toChars(0x200B))
            + new String(Character.toChars(0x202E))
            + "\n";
    assertThat(EolCheck.checkBytes("x.md", text.getBytes(StandardCharsets.UTF_8)))
        .containsExactly("x.md: BIDI_CONTROL (line 3)", "x.md: ZERO_WIDTH (line 2)");
  }

  @Test
  void ordinaryNonAsciiTextAndLookAlikeCharactersPass() {
    String text = "café 中文 €   – — … " + new String(Character.toChars(0x1F600)) + " Â\u0084\n";
    assertThat(EolCheck.checkBytes("A.java", text.getBytes(StandardCharsets.UTF_8))).isEmpty();
  }

  @Test
  void otherFileTypesAreNotScannedForInvisibleCharacters() {
    assertThat(EolCheck.checkBytes("a.bin", withCodePoint(0x202E))).isEmpty();
    assertThat(EolCheck.checkBytes("a.jar", withCodePoint(0x200B))).isEmpty();
    assertThat(EolCheck.checkBytes("a.cmd", withCodePoint(0x2028)))
        .containsExactly("a.cmd: LF_IN_CMD");
  }

  @Test
  void malformedUtf8NeverThrowsAndLooksLikeNothing() {
    byte[] soup = {
      'a', (byte) 0xE2, (byte) 0x80, '\n', (byte) 0xC2, (byte) 0xFF, (byte) 0xEF, (byte) 0xBB, 'b'
    };
    assertThat(EolCheck.checkBytes("A.java", soup)).isEmpty();
  }

  @Test
  void aCleanTreePassesAndATreeWithHitsNamesEachFile(@TempDir Path root) throws IOException {
    Files.createDirectories(root.resolve("src"));
    Files.write(root.resolve("src/Clean.java"), ascii("class Clean {} // café\n"));
    Files.write(root.resolve("README.md"), ascii("# Title\n\nPlain text.\n"));
    Files.write(root.resolve("data.csv"), ascii("a,b\n1,2\n"));
    List<String> clean = List.of("src/Clean.java", "README.md", "data.csv");
    assertThat(EolCheck.check(root, clean)).isEmpty();

    Files.write(root.resolve("src/Bidi.java"), withCodePoint(0x202E));
    Files.write(root.resolve("notes.md"), withCodePoint(0x200B));
    Files.write(root.resolve("table.tsv"), withCodePoint(0x2028));
    List<String> all = new java.util.ArrayList<>(clean);
    all.addAll(List.of("src/Bidi.java", "notes.md", "table.tsv"));
    assertThat(EolCheck.check(root, all))
        .containsExactly(
            "src/Bidi.java: BIDI_CONTROL (line 2)",
            "notes.md: ZERO_WIDTH (line 2)",
            "table.tsv: UNICODE_LINE_SEPARATOR (line 2)");
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
