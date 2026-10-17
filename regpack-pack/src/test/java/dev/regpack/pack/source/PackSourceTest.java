package dev.regpack.pack.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.PackSyntaxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The file tree of a pack: names, limits and the three ways to read one (plan task T6). */
class PackSourceTest {
  private static final byte[] ONE = "k: v\n".getBytes(UTF_8);

  private static Map<String, byte[]> one(String path) {
    return Map.of(path, ONE);
  }

  private static void assertCode(Map<String, byte[]> files, String code) {
    assertThatThrownBy(() -> PackSource.of(files))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  static Stream<Arguments> badNames() {
    return Stream.of(
        Arguments.of("../x.yaml", "PATH_INVALID"), // traversal
        Arguments.of("scope/../x.yaml", "PATH_INVALID"),
        Arguments.of("..", "PATH_INVALID"),
        Arguments.of("/x.yaml", "PATH_INVALID"), // absolute
        Arguments.of("C:/x.yaml", "PATH_INVALID"),
        Arguments.of("C:\\x.yaml", "PATH_INVALID"),
        Arguments.of(".hidden.yaml", "PATH_INVALID"), // hidden
        Arguments.of("scope/.hidden.yaml", "PATH_INVALID"),
        Arguments.of(".git/config.yaml", "PATH_INVALID"),
        Arguments.of("a\\b.yaml", "PATH_INVALID"), // backslash
        Arguments.of("scope\\b.yaml", "PATH_INVALID"),
        Arguments.of("a//b.yaml", "PATH_INVALID"), // empty segment
        Arguments.of("a/.yaml", "PATH_INVALID"),
        Arguments.of("a/", "PATH_INVALID"),
        Arguments.of("", "PATH_INVALID"),
        Arguments.of("a b.yaml", "PATH_INVALID"), // character outside [A-Za-z0-9_.-]
        Arguments.of("caf\u00e9.yaml", "PATH_INVALID"),
        Arguments.of("a\u0000b.yaml", "PATH_INVALID"),
        Arguments.of("a:b.yaml", "PATH_INVALID"),
        Arguments.of("a\nb.yaml", "PATH_INVALID"),
        Arguments.of("scope./b.yaml", "PATH_INVALID"), // a trailing dot is dropped by Windows
        Arguments.of("a".repeat(300) + ".yaml", "PATH_INVALID"));
  }

  @ParameterizedTest
  @MethodSource("badNames")
  void rejectsTraversalAbsoluteHiddenAndBackslashNames(String name, String code) {
    assertCode(one(name), code);
  }

  @Test
  void rejectsCaseCollisions() {
    Map<String, byte[]> files = new HashMap<>();
    files.put("scope/A.yaml", ONE);
    files.put("scope/a.yaml", ONE);
    assertCode(files, "PATH_CASE_COLLISION");

    // a file and a directory of the same name
    Map<String, byte[]> clash = new HashMap<>();
    clash.put("CHECKSUMS", ONE);
    clash.put("checksums/x.yaml", ONE);
    assertCode(clash, "PATH_CASE_COLLISION");
    Map<String, byte[]> clash2 = new HashMap<>();
    clash2.put("CHECKSUMS", ONE);
    clash2.put("CHECKSUMS/x.yaml", ONE);
    assertCode(clash2, "PATH_FILE_AND_DIRECTORY");
  }

  @ParameterizedTest
  @MethodSource("badExtensions")
  void rejectsUnknownExtensions(String name) {
    assertCode(one(name), "PATH_EXTENSION");
  }

  static Stream<String> badExtensions() {
    return Stream.of(
        "a.yml",
        "a.txt",
        "a.exe",
        "a",
        "a.YAML",
        "a.Json",
        "a.yaml.bak",
        "scope/a",
        "x/CHECKSUMS",
        "x/ATTESTATION",
        "checksums",
        "CHECKSUMS.yaml.sig",
        "a.json5");
  }

  @Test
  void acceptsTheAllowedNames() {
    PackSource source =
        PackSource.of(
            Map.of(
                "pack.yaml", ONE,
                "scope/a-b_c.1.yaml", ONE,
                "tests/golden/g1.json", ONE,
                "CHECKSUMS", ONE,
                "ATTESTATION", ONE));
    assertThat(source.paths())
        .containsExactly(
            "ATTESTATION", "CHECKSUMS", "pack.yaml", "scope/a-b_c.1.yaml", "tests/golden/g1.json");
  }

  @Test
  void rejectsBomAndInvalidUtf8() {
    PackSource bom =
        PackSource.of(
            Map.of(
                "a.yaml", new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', ':', ' ', '1'}));
    assertThatThrownBy(() -> bom.text("a.yaml"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_BOM"));

    List<byte[]> invalid =
        List.of(
            new byte[] {'a', (byte) 0xFF},
            new byte[] {(byte) 0xC0, (byte) 0x80}, // overlong NUL
            new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}, // an encoded surrogate
            new byte[] {(byte) 0xE2, (byte) 0x82}, // truncated
            new byte[] {(byte) 0xF8, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80});
    for (byte[] bytes : invalid) {
      PackSource src = PackSource.of(Map.of("a.yaml", bytes));
      assertThatThrownBy(() -> src.text("a.yaml"))
          .isInstanceOfSatisfying(
              PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_BAD_UTF8"));
    }
  }

  @Test
  void textIsStrictUtf8WithCrlfNormalisedToLf() {
    PackSource src =
        PackSource.of(Map.of("a.yaml", "k: caf\u00e9\r\nj: \u00fc\r\n\rx".getBytes(UTF_8)));
    assertThat(src.text("a.yaml")).isEqualTo("k: caf\u00e9\nj: \u00fc\n\rx"); // a lone CR stays
  }

  @Test
  void bytesAreCopiedInAndOut() {
    byte[] original = "k: v\n".getBytes(UTF_8);
    PackSource src = PackSource.of(Map.of("a.yaml", original));
    original[0] = 'X';
    assertThat(src.text("a.yaml")).isEqualTo("k: v\n");
    src.bytes("a.yaml")[0] = 'Y';
    assertThat(src.text("a.yaml")).isEqualTo("k: v\n");
    assertThat(src.has("a.yaml")).isTrue();
    assertThat(src.has("b.yaml")).isFalse();
    assertThatThrownBy(() -> src.bytes("b.yaml")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> src.paths().add("c.yaml"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void enforcesFileCountAndSizeLimits() {
    // exactly at the limits is accepted
    Map<String, byte[]> atCount = new HashMap<>();
    for (int i = 0; i < PackFormat.MAX_FILES; i++) {
      atCount.put("f" + i + ".yaml", ONE);
    }
    assertThat(PackSource.of(atCount).paths()).hasSize(PackFormat.MAX_FILES);
    atCount.put("one-too-many.yaml", ONE);
    assertCode(atCount, "PACK_TOO_MANY_FILES");

    Map<String, byte[]> bigFile = Map.of("a.yaml", new byte[PackFormat.MAX_FILE_BYTES]);
    assertThat(PackSource.of(bigFile).bytes("a.yaml")).hasSize(PackFormat.MAX_FILE_BYTES);
    assertCode(Map.of("a.yaml", new byte[PackFormat.MAX_FILE_BYTES + 1]), "PACK_FILE_TOO_LARGE");

    // sixteen files of 1 MiB are exactly the total limit, a seventeenth is over it
    Map<String, byte[]> total = new HashMap<>();
    for (int i = 0; i < PackFormat.MAX_TOTAL_BYTES / PackFormat.MAX_FILE_BYTES; i++) {
      total.put("f" + i + ".yaml", new byte[PackFormat.MAX_FILE_BYTES]);
    }
    assertThat(PackSource.of(total).paths()).hasSize(16);
    total.put("over.yaml", new byte[1]);
    assertCode(total, "PACK_TOO_LARGE");
  }

  @Test
  void reservedDeviceNamesAreRefused() {
    for (String name :
        List.of(
            "con.yaml",
            "CON.yaml",
            "Prn.yaml",
            "aux.json",
            "NUL.yaml",
            "com1.yaml",
            "COM9.yaml",
            "lpt1.yaml",
            "LPT9.yaml",
            "scope/nul.yaml",
            "con/x.yaml",
            "con.tests.yaml",
            "AUX.x.y.json")) {
      assertCode(one(name), "PATH_RESERVED_NAME");
    }
    // near misses are fine
    PackSource ok =
        PackSource.of(
            Map.of("com0.yaml", ONE, "com10.yaml", ONE, "console.yaml", ONE, "lpt.yaml", ONE));
    assertThat(ok.paths()).hasSize(4);
  }

  @Test
  void nullsAreRefused() {
    assertThatThrownBy(() -> PackSource.of(null)).isInstanceOf(NullPointerException.class);
    Map<String, byte[]> withNull = new HashMap<>();
    withNull.put("a.yaml", null);
    assertThatThrownBy(() -> PackSource.of(withNull)).isInstanceOf(NullPointerException.class);
  }

  // ---------------------------------------------------------------------------------------------

  private static void write(Path dir, String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, UTF_8);
  }

  @Test
  void fromDirectoryUsesForwardSlashes(@TempDir Path dir) throws IOException {
    write(dir, "pack.yaml", "id: x\n");
    write(dir, "scope/main.yaml", "table: {}\n");
    write(dir, "tests/golden/g1.json", "{}\n");

    PackSource src = PackSource.fromDirectory(dir);
    assertThat(src.paths()).containsExactly("pack.yaml", "scope/main.yaml", "tests/golden/g1.json");
    assertThat(src.text("scope/main.yaml")).isEqualTo("table: {}\n");
  }

  @Test
  void fromDirectoryRefusesNamesThatArePathsNoPackMayHave(@TempDir Path dir) throws IOException {
    write(dir, "pack.yaml", "id: x\n");
    write(dir, ".git/config.yaml", "x\n");
    assertThatThrownBy(() -> PackSource.fromDirectory(dir))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PATH_INVALID"));
  }

  @Test
  void fromDirectoryRejectsAWindowsJunction(@TempDir Path dir, @TempDir Path elsewhere)
      throws IOException, InterruptedException {
    assumeTrue(
        System.getProperty("os.name").startsWith("Windows"), "junctions exist on Windows only");
    write(elsewhere, "inner.yaml", "k: v\n");
    write(dir, "pack.yaml", "id: x\n");
    Process mklink =
        new ProcessBuilder(
                "cmd", "/c", "mklink", "/J", dir.resolve("scope").toString(), elsewhere.toString())
            .redirectErrorStream(true)
            .start();
    mklink.getInputStream().readAllBytes();
    assumeTrue(mklink.waitFor() == 0, "mklink /J failed");
    try {
      assertThatThrownBy(() -> PackSource.fromDirectory(dir))
          .isInstanceOfSatisfying(
              PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_NOT_REGULAR"));
    } finally {
      Files.delete(dir.resolve("scope")); // removes the junction, not the target
    }
    assertThat(elsewhere.resolve("inner.yaml")).exists();
  }

  @Test
  void fromDirectoryRejectsSymlinks(@TempDir Path dir, @TempDir Path elsewhere) throws IOException {
    Path target = elsewhere.resolve("real.yaml");
    Files.writeString(target, "k: v\n", UTF_8);
    write(dir, "pack.yaml", "id: x\n");
    try {
      Files.createSymbolicLink(dir.resolve("link.yaml"), target);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      assumeTrue(false, "this platform cannot create a symbolic link: " + e);
    }
    assertThatThrownBy(() -> PackSource.fromDirectory(dir))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_NOT_REGULAR"));
  }

  @Test
  void fromDirectoryRejectsADirectorySymlink(@TempDir Path dir, @TempDir Path elsewhere)
      throws IOException {
    write(elsewhere, "inner.yaml", "k: v\n");
    try {
      Files.createSymbolicLink(dir.resolve("scope"), elsewhere);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      assumeTrue(false, "this platform cannot create a symbolic link: " + e);
    }
    assertThatThrownBy(() -> PackSource.fromDirectory(dir))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_NOT_REGULAR"));
  }

  @Test
  void fromDirectoryChecksSizeBeforeReading(@TempDir Path dir) throws IOException {
    // 2 GiB + 1 byte: a read of the whole file would fail with an OutOfMemoryError or take
    // very long; the size in the file attributes is checked first
    Path big = dir.resolve("big.yaml");
    try (RandomAccessFile raf = new RandomAccessFile(big.toFile(), "rw")) {
      raf.setLength(Integer.MAX_VALUE + 1L);
    } catch (IOException e) {
      assumeTrue(false, "cannot make a large sparse file here: " + e);
    }
    try {
      assertThatThrownBy(() -> PackSource.fromDirectory(dir))
          .isInstanceOfSatisfying(
              PackSyntaxException.class,
              e -> assertThat(e.code()).isEqualTo("PACK_FILE_TOO_LARGE"));
    } finally {
      Files.deleteIfExists(big);
    }
  }

  @Test
  void fromDirectoryStopsAtTooManyFiles(@TempDir Path dir) throws IOException {
    for (int i = 0; i <= PackFormat.MAX_FILES; i++) {
      write(dir, "f" + i + ".yaml", "k: v\n");
    }
    assertThatThrownBy(() -> PackSource.fromDirectory(dir))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PACK_TOO_MANY_FILES"));
  }

  @Test
  void fromDirectoryRefusesAMissingDirectory(@TempDir Path dir) {
    assertThatThrownBy(() -> PackSource.fromDirectory(dir.resolve("nope")))
        .isInstanceOf(UncheckedIOException.class);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void fromClasspathReadsOnlyTheFilesTheChecksumsList() {
    PackSource src = PackSource.fromClasspath(PackSourceTest.class, "dev/regpack/pack/source/mini");
    // extra.yaml sits in the resource folder but is not listed in CHECKSUMS
    assertThat(src.paths()).containsExactly("CHECKSUMS", "a.yaml", "scope/b.yaml");
    assertThat(src.text("a.yaml")).isEqualTo("alpha: 1\n");
    List<PackProblem> problems = Checksums.verify(src);
    assertThat(problems).isEmpty();
  }

  @Test
  void fromClasspathRefusesTraversalNamesInChecksums() {
    assertThatThrownBy(
            () ->
                PackSource.fromClasspath(PackSourceTest.class, "dev/regpack/pack/source/traversal"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PATH_INVALID"));
  }

  @Test
  void fromClasspathRefusesABadRootAndAMissingChecksums() {
    assertThatThrownBy(() -> PackSource.fromClasspath(PackSourceTest.class, "../x"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("PATH_INVALID"));
    assertThatThrownBy(() -> PackSource.fromClasspath(PackSourceTest.class, "no/such/pack"))
        .isInstanceOfSatisfying(
            PackSyntaxException.class, e -> assertThat(e.code()).isEqualTo("CHECKSUMS_MISSING"));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void sealRewritesChecksumsAndIsIdempotent(@TempDir Path dir) throws IOException {
    write(dir, "pack.yaml", "id: x\n");
    write(dir, "scope/main.yaml", "table: {}\r\n");
    write(dir, "CHECKSUMS", "stale\n");

    assertThat(Seal.seal(dir)).isEqualTo(2);
    byte[] first = Files.readAllBytes(dir.resolve("CHECKSUMS"));
    assertThat(Checksums.verify(PackSource.fromDirectory(dir))).isEmpty();
    assertThat(new String(first, UTF_8)).isEqualTo(Checksums.render(PackSource.fromDirectory(dir)));
    assertThat(new String(first, UTF_8)).doesNotContain("\r").endsWith("\n");

    assertThat(Seal.seal(dir)).isEqualTo(2);
    assertThat(Files.readAllBytes(dir.resolve("CHECKSUMS"))).isEqualTo(first);

    write(dir, "pack.yaml", "id: y\n");
    assertThat(Checksums.verify(PackSource.fromDirectory(dir))).isNotEmpty();
    Seal.seal(dir);
    assertThat(Files.readAllBytes(dir.resolve("CHECKSUMS"))).isNotEqualTo(first);
    assertThat(Checksums.verify(PackSource.fromDirectory(dir))).isEmpty();
  }

  @Test
  void sealWorksOnAnEmptyDirectoryAndMainPrintsTheCount(@TempDir Path dir) throws IOException {
    assertThat(Seal.seal(dir)).isZero();
    assertThat(Files.readString(dir.resolve("CHECKSUMS"))).isEmpty();

    write(dir, "pack.yaml", "id: x\n");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int status =
        Seal.run(
            new String[] {dir.toString()},
            new PrintStream(out, true, UTF_8),
            new PrintStream(err, true, UTF_8));
    assertThat(status).isZero();
    assertThat(out.toString(UTF_8)).isEqualTo("sealed 1 files\n");

    int usage =
        Seal.run(
            new String[] {}, new PrintStream(out, true, UTF_8), new PrintStream(err, true, UTF_8));
    assertThat(usage).isEqualTo(2);
    assertThat(err.toString(UTF_8)).contains("usage");
  }
}
