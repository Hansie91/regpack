package dev.regpack.app;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.source.PackSource;
import dev.regpack.pack.source.Seal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code pack} commands through {@link Main#run}, on temporary copies of the sample pack (plan
 * T25, review focus PF21: the exit code follows the checks, the unsigned state is never hidden).
 */
class PackCommandsTest {
  private static final String SAMPLE_DIGEST =
      "a421f125754b61c1682ab26021611b380e5c97409adb97f125175c1d4ac0b4f6";
  private static final String SCOPE = "scope/sample-scope.yaml";
  private static final String GOLDEN = "tests/golden/GC-N03-1.json";
  private static final String CONSISTENCY = "\"provenance\": {\"kind\": \"CONSISTENCY_ONLY\"},";
  private static final String EVENT =
      "{\"firmLei\":\"LEI-FIRM-0001\",\"venueMic\":\"XPAR\",\"venueExecutionId\":\"EX-1\","
          + "\"eventKind\":\"NOVATION\",\"tradedAt\":\"2026-10-09T09:30:00.123456Z\","
          + "\"assetClass\":\"EQUITY\",\"isin\":\"ZZ0000000001\",\"tradedOnVenue\":true,"
          + "\"clientKind\":\"LEGAL_ENTITY\",\"clientLei\":\"LEI-CLIENT-0001\","
          + "\"quantity\":100,\"price\":12.25,\"currency\":\"EUR\"}";

  @TempDir Path temp;

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, UTF_8);
  private Path pack;

  @BeforeEach
  void copySamplePack() throws IOException {
    pack = temp.resolve("pack");
    Path sample =
        Path.of(System.getProperty("regpack.repo.root")).resolve("packs").resolve("sample.s0");
    try (Stream<Path> files = Files.walk(sample)) {
      for (Path file : files.toList()) {
        Path target = pack.resolve(sample.relativize(file).toString());
        if (Files.isDirectory(file)) {
          Files.createDirectories(target);
        } else {
          Files.write(target, Files.readAllBytes(file));
        }
      }
    }
  }

  private int run(String... args) {
    return Main.run(args, Map.of(), out, err);
  }

  private String stdout() {
    return outBytes.toString(UTF_8);
  }

  private String stderr() {
    return errBytes.toString(UTF_8);
  }

  private List<String> stdoutLines() {
    return stdout().lines().toList();
  }

  private String text(String path) throws IOException {
    return Files.readString(pack.resolve(path), UTF_8);
  }

  private void write(String path, String text) throws IOException {
    Path file = pack.resolve(path);
    Files.createDirectories(file.getParent());
    Files.write(file, text.getBytes(UTF_8));
  }

  /** Replaces a text in a pack file, failing when it is not there, and re-seals the pack. */
  private void replaceAndSeal(String path, String from, String to) throws IOException {
    String before = text(path);
    assertThat(before).contains(from);
    write(path, before.replace(from, to));
    Seal.seal(pack);
  }

  /** Every file of a directory as ISO-8859-1 text, by relative path. */
  private static Map<String, String> snapshot(Path dir) throws IOException {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        files.put(
            dir.relativize(file).toString().replace('\\', '/'),
            new String(Files.readAllBytes(file), ISO_8859_1));
      }
    }
    return files;
  }

  private static String last(List<String> lines) {
    return lines.get(lines.size() - 1);
  }

  // ---- pack test ------------------------------------------------------------------------------

  @Test
  void testPassesOnTheSamplePackAndPrintsTheDocumentedLines() {
    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isZero();
    assertThat(stdoutLines())
        .containsExactly(
            "pack test: sample.s0 0.1.0 digest " + SAMPLE_DIGEST,
            "files: 11 (CHECKSUMS verified)",
            "signature: UNSIGNED (an ATTESTATION is not verified before P9b)",
            "static checks: 0 problems",
            "engine: 1.0.0+lib1 satisfies engine_min 1.0.0+lib1",
            "golden: 4 of 4 pass",
            "coverage: rows 4/4, reasons 4/4, provisions 4/4 (all cases);"
                + " rows 0/4 (counting cases)",
            "audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 0"
                + " from none (empty registry) (gate G1: no named Compliance owner)",
            "budget: record budget 40 units (static worst case 20); largest rule 5 nodes;"
                + " rule ceiling 512; SP-R recommended 64",
            "pack test: PASS");
    assertThat(stderr()).isEmpty();
    assertThat(stdout()).endsWith("pack test: PASS\n").doesNotContain("\r");
  }

  @Test
  void testExitsOneOnAFailingGoldenCase() throws IOException {
    replaceAndSeal(GOLDEN, "\"outcome\": \"DO_NOT_REPORT\"", "\"outcome\": \"REPORT\"");

    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isEqualTo(1);
    assertThat(stdout()).contains("problem GOLDEN_CASE_FAILED " + GOLDEN);
    assertThat(last(stdoutLines())).startsWith("pack test: FAIL (");
  }

  @Test
  void testExitsOneWhenAProvenanceClaimHasNoReviewer() throws IOException {
    replaceAndSeal(
        GOLDEN,
        CONSISTENCY,
        "\"provenance\": {\"kind\": \"ESMA_QA\", \"source\": \"synthetic-source\","
            + " \"reviewedOn\": \"2026-10-01\"},");

    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isEqualTo(1);
    assertThat(stdout()).contains("problem GOLDEN_NO_REVIEWER " + GOLDEN);
    assertThat(last(stdoutLines())).startsWith("pack test: FAIL (");
  }

  @Test
  void testExitsOneForAnUnregisteredReviewer() throws IOException {
    replaceAndSeal(
        GOLDEN,
        CONSISTENCY,
        "\"provenance\": {\"kind\": \"ESMA_QA\", \"source\": \"synthetic-source\","
            + " \"reviewer\": \"reviewer.test\", \"reviewedOn\": \"2026-10-01\"},");
    Path none = temp.resolve("none.txt");
    Files.writeString(none, "# no reviewers\n", UTF_8);
    Path registered = temp.resolve("registered.txt");
    Files.writeString(registered, "reviewer.test|Test Reviewer|synthetic\n", UTF_8);

    assertThat(run("pack", "test", pack.toString(), "--reviewers", none.toString())).isEqualTo(1);
    assertThat(stdout())
        .contains("problem GOLDEN_AUDIT " + GOLDEN)
        .contains("REVIEWER_NOT_REGISTERED");

    outBytes.reset();
    assertThat(run("pack", "test", pack.toString(), "--reviewers", registered.toString())).isZero();
    assertThat(stdout()).contains("audit: 1 of 4 counting").endsWith("pack test: PASS\n");
  }

  @Test
  void testExitsTwoOnUsageErrors() throws IOException {
    Path file = temp.resolve("not-a-directory.txt");
    Files.writeString(file, "x", UTF_8);
    List<String[]> bad =
        List.of(
            new String[] {"pack"},
            new String[] {"pack", "frobnicate"},
            new String[] {"pack", "test"},
            new String[] {"pack", "test", pack.toString(), "extra"},
            new String[] {"pack", "test", pack.toString(), "--reviewers"},
            new String[] {"pack", "test", pack.toString(), "--bogus", "x"},
            new String[] {"pack", "test", temp.resolve("no-such-directory").toString()},
            new String[] {"pack", "test", file.toString()},
            new String[] {
              "pack", "test", pack.toString(), "--reviewers", temp.resolve("no").toString()
            },
            new String[] {"pack", "explain", pack.toString()},
            new String[] {"pack", "explain", pack.toString(), "--rule"},
            new String[] {"pack", "explain", pack.toString(), "--rule", "a", "--event", "b"},
            new String[] {"pack", "explain", pack.toString(), "--rule", "no-such-rule"},
            new String[] {
              "pack", "explain", pack.toString(), "--event", temp.resolve("no").toString()
            },
            new String[] {"pack", "seal"},
            new String[] {"pack", "seal", pack.toString(), "extra"},
            new String[] {"pack", "seal", temp.resolve("no-such-directory").toString()},
            new String[] {"pack", "table"},
            new String[] {"pack", "table", "frobnicate"},
            new String[] {"pack", "table", "export", pack.toString()},
            new String[] {"pack", "table", "export", pack.toString(), SCOPE, "--out"},
            new String[] {"pack", "table", "import", pack.toString(), SCOPE},
            new String[] {"pack", "table", "import", pack.toString(), SCOPE, "x.csv", "--bogus"});
    Map<String, String> before = snapshot(pack);

    for (String[] args : bad) {
      outBytes.reset();
      errBytes.reset();
      assertThat(run(args)).as(String.join(" ", args)).isEqualTo(2);
      assertThat(stdout()).as(String.join(" ", args)).isEmpty();
      assertThat(stderr()).as(String.join(" ", args)).isNotBlank();
    }
    assertThat(snapshot(pack)).isEqualTo(before);
  }

  @Test
  void testExitsOneForAPackThatDoesNotLoad() throws IOException {
    write(SCOPE, text(SCOPE).replace("priority: 400", "priority: 300"));
    Seal.seal(pack);

    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isEqualTo(1);
    assertThat(stdout()).contains("problem PRIORITY_DUPLICATE " + SCOPE);
    assertThat(stdout()).doesNotContain("golden:");
  }

  @Test
  void testReportsUnsignedAndNotInterpretedSections() throws IOException {
    write("lifecycle.yaml", "business_key: [event.kind]\n");
    Seal.seal(pack);

    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isZero();
    assertThat(stdoutLines())
        .contains(
            "signature: UNSIGNED (an ATTESTATION is not verified before P9b)",
            "not interpreted by this engine build: lifecycle.yaml");
  }

  @Test
  void testRefusesAStaleChecksumsFile() throws IOException {
    write(GOLDEN, text(GOLDEN).replace("A novation", "The novation"));

    int exit = run("pack", "test", pack.toString());

    assertThat(exit).isEqualTo(1);
    assertThat(stdout()).contains("problem CHECKSUM_MISMATCH " + GOLDEN);
  }

  @Test
  void theAuditLineNamesTheRegistryThatWasUsed() throws IOException {
    Path registry = temp.resolve("reviewers.txt");
    Files.writeString(registry, "reviewer.test|Test Reviewer|synthetic\n", UTF_8);

    assertThat(run("pack", "test", pack.toString(), "--reviewers", registry.toString())).isZero();

    String resolved = registry.toAbsolutePath().normalize().toString();
    assertThat(stdoutLines())
        .contains(
            "audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 1 from " + resolved);

    // the path is shown resolved, whatever form it was given in
    outBytes.reset();
    Path dotted = temp.resolve("sub").resolve("..").resolve("reviewers.txt");
    Files.createDirectories(temp.resolve("sub"));
    assertThat(run("pack", "test", pack.toString(), "--reviewers", dotted.toString())).isZero();
    assertThat(stdout()).contains("reviewers registered: 1 from " + resolved + "\n");
  }

  @Test
  void theDefaultRegistryIsReadFromTheWorkingDirectoryAndNamedInTheAuditLine() {
    // the JVM working directory of the tests is the module directory, which has no governance/
    Path none = Path.of("governance/golden-reviewers.txt").toAbsolutePath();
    org.junit.jupiter.api.Assumptions.assumeFalse(Files.exists(none));

    assertThat(run("pack", "test", pack.toString())).isZero();

    assertThat(stdoutLines())
        .contains(
            "audit: 0 of 4 counting, 4 consistency-only; reviewers registered: 0 from none (empty"
                + " registry) (gate G1: no named Compliance owner)");
  }

  // ---- pack explain ---------------------------------------------------------------------------

  @Test
  void explainRulePrintsReasonCitationsAndRationale() {
    int exit = run("pack", "explain", pack.toString(), "--rule", "S-N03-novation");

    assertThat(exit).isZero();
    assertThat(stdout())
        .contains("S-N03-novation")
        .contains("SAMPLE.N03.EXCLUDED")
        .contains("sample-law")
        .contains("RAT-0001")
        .doesNotContain("\r");
    assertThat(stderr()).isEmpty();
  }

  @Test
  void explainEventPrintsTierOneAndTheTrace() throws IOException {
    Path event = temp.resolve("event.json");
    Files.writeString(event, EVENT, UTF_8);

    int exit = run("pack", "explain", pack.toString(), "--event", event.toString());

    assertThat(exit).isZero();
    assertThat(stdoutLines())
        .contains(
            "Decision 0 (seq 1) for LEI-FIRM-0001|XPAR|EX-1 under sample.s0 (not stored)",
            "Outcome: DO_NOT_REPORT",
            "Trace (full):",
            "  1. S-N03-novation: MATCH (8 units): event.kind in ['NOVATION','ASSIGNMENT']");
    assertThat(last(stdoutLines()))
        .isEqualTo("Tier 2: no stored decision; evaluated on the current engine 1.0.0+lib1");
    assertThat(stderr()).isEmpty();
  }

  /** Every character of the output is printable ASCII or LF, and no line is over the bound. */
  private void assertSafeOutput(String text, String what) {
    assertThat(text.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)))
        .as(what + " is printable ASCII and LF only")
        .isTrue();
    assertThat(text.lines().mapToInt(String::length).max().orElse(0))
        .as(what + " line length")
        .isLessThanOrEqualTo(PackCommands.MAX_OUTPUT_LINE + PackCommands.CUT_MARKER.length());
  }

  /**
   * FP2: the identity of an event is a key text, so the codec now refuses a control character such
   * as ESC and an identity over 512 characters (the next test); the characters it still allows (a
   * C1 control, a right-to-left override) are escaped, and the line is cut at the bound after
   * escaping, as before.
   */
  @Test
  void explainEventEscapesAndBoundsTheEventIdentity() throws IOException {
    String hostile = "LEI" + CSI + "[31m" + RLO + "X";
    Path event = temp.resolve("hostile-event.json");
    // the identity is JSON-escaped in the file; 300 override characters take 1,800 characters once
    // escaped, far over the line bound, while the identity itself stays inside its own bound
    Files.writeString(
        event, EVENT.replace("LEI-FIRM-0001", jsonEscaped(hostile + RLO.repeat(300))), UTF_8);

    int exit = run("pack", "explain", pack.toString(), "--event", event.toString());

    assertThat(exit).isZero();
    assertThat(stdout()).contains("Decision 0 (seq 1) for LEI\\u009b[31m\\u202eX\\u202e\\u202e");
    assertThat(stdout()).contains(PackCommands.CUT_MARKER);
    assertThat(stdout()).doesNotContain(CSI).doesNotContain(RLO);
    assertThat(stdout().length()).isLessThan(20_000);
    assertSafeOutput(stdout(), "explain --event, firmLei");
    assertThat(stderr()).isEmpty();

    // the same for the execution id, which comes last on the identity line
    outBytes.reset();
    Files.writeString(
        event, EVENT.replace("EX-1", jsonEscaped("EX" + CSI + RLO + RLO.repeat(300))), UTF_8);

    assertThat(run("pack", "explain", pack.toString(), "--event", event.toString())).isZero();
    assertThat(stdout()).contains("for LEI-FIRM-0001|XPAR|EX\\u009b\\u202e\\u202e\\u202e");
    assertThat(stdout()).contains(PackCommands.CUT_MARKER);
    assertSafeOutput(stdout(), "explain --event, venueExecutionId");
  }

  /** FP2: an identity with a C0 control character or over 512 characters is not an execution. */
  @Test
  void explainEventRefusesAControlCharacterAndAnOverLongIdentity() throws IOException {
    Path event = temp.resolve("refused-event.json");
    String[] refused = {
      jsonEscaped("LEI" + ESC + "[31m"), jsonEscaped("LEI" + (char) 0 + "X"), "L".repeat(500_000)
    };
    for (String firmLei : refused) {
      outBytes.reset();
      errBytes.reset();
      Files.writeString(event, EVENT.replace("LEI-FIRM-0001", firmLei), UTF_8);

      int exit = run("pack", "explain", pack.toString(), "--event", event.toString());

      assertThat(exit).isEqualTo(2);
      assertThat(stdout()).isEmpty();
      assertThat(stderr()).contains("the event file is not a canonical execution");
      assertThat(stderr()).doesNotContain(ESC);
      assertSafeOutput(stderr(), "explain --event, refused identity");
    }
  }

  @Test
  void explainRuleEscapesTextFromThePackFiles() throws IOException {
    String accented = "caf" + (char) 0xE9;
    replaceAndSeal(
        "rationales.yaml",
        "A novation or assignment is not a new trade",
        accented + " " + "w".repeat(3_000));

    int exit = run("pack", "explain", pack.toString(), "--rule", "S-N03-novation");

    assertThat(exit).isZero();
    assertThat(stdout()).contains("Rationale RAT-0001: caf\\u00e9 www");
    assertThat(stdout()).contains(PackCommands.CUT_MARKER);
    assertSafeOutput(stdout(), "explain --rule");
  }

  private static final String ESC = String.valueOf((char) 0x1B);
  private static final String RLO = new String(Character.toChars(0x202E));
  private static final String CSI = String.valueOf((char) 0x9B);

  /** The text as the body of a JSON string: every non-printable character as a JSON escape. */
  private static String jsonEscaped(String text) {
    StringBuilder out = new StringBuilder();
    for (char c : text.toCharArray()) {
      if (c < 0x20 || c > 0x7E) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  @Test
  void printableEscapesEverythingOutsideAsciiAndCuts() {
    assertThat(PackCommands.printable("ab", 10)).isEqualTo("ab");
    assertThat(PackCommands.printable(null, 10)).isEmpty();
    assertThat(PackCommands.printable("", 10)).isEmpty();
    // the edges of the printable range: 0x1F, 0x20, 0x7E, 0x7F
    assertThat(
            PackCommands.printable("" + (char) 0x1F + (char) 0x20 + (char) 0x7E + (char) 0x7F, 99))
        .isEqualTo("\\u001f ~\\u007f");
    assertThat(PackCommands.printable("a" + ESC + RLO + (char) 0xE9 + "\n\t", 99))
        .isEqualTo("a\\u001b\\u202e\\u00e9\\u000a\\u0009");
    // a supplementary character is escaped per UTF-16 unit
    assertThat(PackCommands.printable(new String(Character.toChars(0x1F600)), 99))
        .isEqualTo("\\ud83d\\ude00");
    // cut at the bound, never inside an escape
    assertThat(PackCommands.printable("abcdef", 3)).isEqualTo("abc");
    assertThat(PackCommands.printable("ab" + RLO + "cd", 7)).isEqualTo("ab");
    assertThat(PackCommands.printable("ab" + RLO + "cd", 8)).isEqualTo("ab\\u202e");
    assertThat(PackCommands.printable("abc", 0)).isEmpty();
  }

  @Test
  void safeOutputEscapesEachLineAndMarksACut() {
    int max = PackCommands.MAX_OUTPUT_LINE;
    String marker = PackCommands.CUT_MARKER;

    assertThat(PackCommands.safeOutput("a\nb")).isEqualTo("a\nb");
    assertThat(PackCommands.safeOutput("")).isEmpty();
    assertThat(PackCommands.safeOutput("a\n\nb\n")).isEqualTo("a\n\nb\n");
    assertThat(PackCommands.safeOutput("x" + ESC + "\r\ty" + RLO))
        .isEqualTo("x\\u001b\\u000d\\u0009y\\u202e");
    // a line of exactly the bound is kept whole, one more character is cut and marked
    assertThat(PackCommands.safeOutput("z".repeat(max))).isEqualTo("z".repeat(max));
    assertThat(PackCommands.safeOutput("z".repeat(max + 1))).isEqualTo("z".repeat(max) + marker);
    // the bound counts escaped characters; an escape that does not fit is dropped whole
    String escapes = RLO.repeat(max / 6 + 1);
    String cut = PackCommands.safeOutput("a\n" + escapes + "\nb");
    assertThat(cut.split("\n", -1)).hasSize(3);
    assertThat(cut.split("\n", -1)[1])
        .endsWith(marker)
        .hasSizeLessThanOrEqualTo(max + marker.length());
    assertThat(cut.split("\n", -1)[2]).isEqualTo("b");
    // an escape that ends exactly at the bound is not a cut
    assertThat(PackCommands.safeOutput("q".repeat(max - 6) + RLO))
        .isEqualTo("q".repeat(max - 6) + "\\u202e");
  }

  @Test
  void aNameWithControlAndBidiCharactersIsEchoedOnlyAsEscapes() {
    String name = "d" + ESC + RLO + (char) 0xE9;
    List<String[]> commands =
        List.of(
            new String[] {"pack", "test", name},
            new String[] {"pack", name},
            new String[] {"pack", "table", name},
            new String[] {"pack", "test", pack.toString(), "--reviewers", name},
            new String[] {"pack", "test", pack.toString(), name});

    for (String[] args : commands) {
      errBytes.reset();
      assertThat(run(args)).as(String.join(" ", args)).isEqualTo(2);
      assertThat(stderr()).as(String.join(" ", args)).contains("d\\u001b\\u202e\\u00e9");
      assertThat(stderr().chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E))).isTrue();
    }
  }

  @Test
  void theOptionsOfPackTestAreParsedStrictly() throws IOException {
    Path registry = temp.resolve("registry.txt");
    Files.writeString(registry, "# none\n", UTF_8);
    String reg = registry.toString();
    String dir = pack.toString();
    // arguments, then the text the usage error must contain
    List<String[]> refused =
        List.of(
            new String[] {
              "unknown option", "unexpected argument '--bogus'", "pack", "test", dir, "--bogus", "x"
            },
            new String[] {
              "unknown option after a good one",
              "unexpected argument '--bogus'",
              "pack",
              "test",
              dir,
              "--reviewers",
              reg,
              "--bogus",
              "x"
            },
            new String[] {
              "missing value", "--reviewers needs a file", "pack", "test", dir, "--reviewers"
            },
            new String[] {
              "repeated option",
              "unexpected argument '--reviewers'",
              "pack",
              "test",
              dir,
              "--reviewers",
              reg,
              "--reviewers",
              reg
            },
            new String[] {
              "a stray value", "unexpected argument 'extra'", "pack", "test", dir, "extra"
            },
            new String[] {
              "a stray value after the option",
              "unexpected argument 'extra'",
              "pack",
              "test",
              dir,
              "--reviewers",
              reg,
              "extra"
            },
            new String[] {
              "the option before the directory",
              "not a directory",
              "pack",
              "test",
              "--reviewers",
              reg,
              dir
            },
            new String[] {
              "double dash alone", "unexpected argument '--'", "pack", "test", dir, "--"
            },
            new String[] {
              "double dash before the option",
              "unexpected argument '--'",
              "pack",
              "test",
              dir,
              "--",
              "--reviewers",
              reg
            },
            new String[] {
              "double dash as the directory", "not a directory", "pack", "test", "--", dir
            });

    for (String[] c : refused) {
      outBytes.reset();
      errBytes.reset();
      String[] args = java.util.Arrays.copyOfRange(c, 2, c.length);
      assertThat(run(args)).as(c[0]).isEqualTo(2);
      assertThat(stderr()).as(c[0]).contains(c[1]);
      assertThat(stdout()).as(c[0]).isEmpty();
    }
    // the same options in the accepted form
    outBytes.reset();
    errBytes.reset();
    assertThat(run("pack", "test", dir, "--reviewers", reg)).isZero();
    // an option value that looks like an option is a file name, and a missing file is refused
    assertThat(run("pack", "test", dir, "--reviewers", "--reviewers")).isEqualTo(2);
    assertThat(stderr()).contains("not a readable file");
    // for explain, the id after --rule is taken as the id, whatever it looks like
    errBytes.reset();
    assertThat(run("pack", "explain", dir, "--rule", "--event")).isEqualTo(2);
    assertThat(stderr()).contains("no rule with the id '--event'");
    // for table export, --out comes after the table path
    errBytes.reset();
    assertThat(
            run("pack", "table", "export", dir, "--out", temp.resolve("o.csv").toString(), SCOPE))
        .isEqualTo(2);
    assertThat(stderr()).contains("usage: regpack pack");
  }

  @Test
  void explainEventRefusesAnInvalidOrOversizedEventFile() throws IOException {
    Path broken = temp.resolve("broken.json");
    Files.writeString(broken, EVENT.replace("NOVATION", "FROBNICATED"), UTF_8);
    Path huge = temp.resolve("huge.json");
    Files.write(huge, new byte[PackFormat.MAX_FILE_BYTES + 1]);

    assertThat(run("pack", "explain", pack.toString(), "--event", broken.toString())).isEqualTo(2);
    assertThat(stderr()).contains("eventKind");
    errBytes.reset();
    assertThat(run("pack", "explain", pack.toString(), "--event", huge.toString())).isEqualTo(2);
    assertThat(stderr()).contains("larger than");
    assertThat(stdout()).isEmpty();
  }

  private static final String[][] UNREPRESENTABLE = {
    {
      "\"tradedAt\":\"2026-10-09T09:30:00.123456Z\"",
      "\"tradedAt\":\"2026-10-09T09:30:00.123456789Z\""
    },
    {"\"tradedAt\":\"2026-10-09T09:30:00.123456Z\"", "\"tradedAt\":\"+10000-01-01T00:00:00Z\""},
    {"\"venueExecutionId\":\"EX-1\"", "\"venueExecutionId\":\"EX|1\""},
  };

  @Test
  void explainEventReportsAnUnrepresentableEventAndNeverThrows() throws IOException {
    for (String[] change : UNREPRESENTABLE) {
      Path event = temp.resolve("bad-event.json");
      Files.writeString(event, EVENT.replace(change[0], change[1]), UTF_8);
      outBytes.reset();
      errBytes.reset();

      int exit = run("pack", "explain", pack.toString(), "--event", event.toString());

      assertThat(exit).as(change[1]).isEqualTo(2);
      assertThat(stderr()).as(change[1]).contains("the event file is not a canonical execution");
      assertThat(stderr()).doesNotContain("Exception").doesNotContain("\tat ");
      assertThat(stdout()).isEmpty();
    }
  }

  @Test
  void testReportsAnUnrepresentableGoldenInputAndNeverThrows() throws IOException {
    String goldenInput = text(GOLDEN);
    for (String[] change : UNREPRESENTABLE) {
      String from = change[0].replace(":\"", ": \"");
      String to = change[1].replace(":\"", ": \"");
      write(GOLDEN, goldenInput.replace(from, to));
      assertThat(text(GOLDEN)).as(change[1]).isNotEqualTo(goldenInput);
      Seal.seal(pack);
      outBytes.reset();
      errBytes.reset();

      int exit = run("pack", "test", pack.toString());

      assertThat(exit).as(change[1]).isEqualTo(1);
      assertThat(stdout()).contains("problem GOLDEN_INPUT_INVALID " + GOLDEN);
      assertThat(last(stdoutLines())).startsWith("pack test: FAIL (");
      assertThat(stderr()).isEmpty();
    }
  }

  // ---- pack seal ------------------------------------------------------------------------------

  @Test
  void sealRewritesChecksumsAndTestThenPasses() throws IOException {
    write(GOLDEN, text(GOLDEN).replace("A novation", "The novation"));
    assertThat(run("pack", "test", pack.toString())).isEqualTo(1);
    outBytes.reset();

    assertThat(run("pack", "seal", pack.toString())).isZero();
    assertThat(stdout()).isEqualTo("sealed 11 files\n");
    outBytes.reset();

    assertThat(run("pack", "test", pack.toString())).isZero();
    assertThat(last(stdoutLines())).isEqualTo("pack test: PASS");
  }

  // ---- pack table -----------------------------------------------------------------------------

  @Test
  void tableExportThenImportLeavesThePackUnchanged() throws IOException {
    Map<String, String> before = snapshot(pack);
    Path csv = temp.resolve("table.csv");

    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    assertThat(Files.readString(csv, UTF_8))
        .startsWith("id,node,priority,when,outcome,reason,rule_ref,cite,rationale,")
        .doesNotContain("\r");
    assertThat(snapshot(pack)).isEqualTo(before);
    outBytes.reset();

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isZero();
    assertThat(stdoutLines()).contains("rows +0 -0 ~0");
    assertThat(run("pack", "seal", pack.toString())).isZero();

    assertThat(snapshot(pack)).isEqualTo(before);
    outBytes.reset();
    assertThat(run("pack", "test", pack.toString())).isZero();
  }

  @Test
  void tableExportToStandardOutputIsThePureCsv() throws IOException {
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE)).isZero();

    String csv = stdout();
    Path file = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", file.toString()))
        .isZero();
    assertThat(csv).isEqualTo(Files.readString(file, UTF_8));
    assertThat(csv.lines().count()).isEqualTo(5);
    assertThat(stderr()).isEmpty();
  }

  @Test
  void tableExportRefusesAnOutputInsideThePack() throws IOException {
    Map<String, String> before = snapshot(pack);
    String dir = pack.toString();
    List<String> targets =
        List.of(
            dir + "/pack.yaml",
            dir + "/CHECKSUMS",
            dir + "/scope/sample-scope.csv",
            dir + "/scope/sample-scope.yaml",
            dir + "/scope/../pack.yaml",
            dir + "/new-directory/table.csv",
            dir + "/scope",
            dir,
            // a relative spelling that leads back into the pack
            temp.resolve("elsewhere").resolve("..").resolve("pack").resolve("x.csv").toString());

    for (String target : targets) {
      outBytes.reset();
      errBytes.reset();
      assertThat(run("pack", "table", "export", dir, SCOPE, "--out", target))
          .as(target)
          .isEqualTo(2);
      assertThat(stderr()).as(target).contains("OUT_INSIDE_PACK");
      assertThat(stdout()).as(target).isEmpty();
    }
    assertThat(snapshot(pack)).isEqualTo(before);
    assertThat(pack.resolve("new-directory")).doesNotExist();
    // the pack still tests clean after all the refusals
    assertThat(run("pack", "test", dir)).isZero();
  }

  @Test
  void tableExportRefusesAnOutputThatReachesThePackThroughALink() throws IOException {
    Path link = temp.resolve("link-to-pack");
    try {
      Files.createSymbolicLink(link, pack);
    } catch (IOException | UnsupportedOperationException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are not available");
    }
    Map<String, String> before = snapshot(pack);

    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", link + "/pack.yaml"))
        .isEqualTo(2);
    assertThat(stderr()).contains("OUT_INSIDE_PACK");
    errBytes.reset();
    // a link outside the pack that points at a file inside it
    Path fileLink = temp.resolve("link-to-file.csv");
    Files.createSymbolicLink(fileLink, pack.resolve("pack.yaml"));
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", fileLink.toString()))
        .isEqualTo(2);
    assertThat(stderr()).contains("OUT_INSIDE_PACK");
    assertThat(snapshot(pack)).isEqualTo(before);
  }

  @Test
  void tableExportOverwritesAnOutputFileOutsideThePackAndLeavesNoTemporaryFile()
      throws IOException {
    Path outDir = temp.resolve("exports");
    Files.createDirectories(outDir);
    Path csv = outDir.resolve("table.csv");
    Files.writeString(csv, "old content that is longer than nothing", UTF_8);

    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();

    assertThat(Files.readString(csv, UTF_8)).startsWith("id,node,priority,");
    try (Stream<Path> files = Files.list(outDir)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("table.csv");
    }
    // an output in a directory that does not exist is a normal refusal, nothing is created
    errBytes.reset();
    assertThat(
            run(
                "pack",
                "table",
                "export",
                pack.toString(),
                SCOPE,
                "--out",
                outDir.resolve("missing").resolve("t.csv").toString()))
        .isEqualTo(2);
    assertThat(stderr()).contains("cannot write");
    assertThat(outDir.resolve("missing")).doesNotExist();
  }

  private static boolean posix() {
    return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  @Test
  void tableImportKeepsThePermissionsOfTheTableFile() throws IOException {
    org.junit.jupiter.api.Assumptions.assumeTrue(posix(), "POSIX permissions");
    Path table = pack.resolve(SCOPE);
    Set<PosixFilePermission> mode = PosixFilePermissions.fromString("rw-r-----");
    Files.setPosixFilePermissions(table, mode);
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isZero();

    assertThat(Files.getPosixFilePermissions(table)).isEqualTo(mode);
    // a new output file gets the default of the process (the umask), not the 0600 of a temp file
    Path plain = Files.createFile(temp.resolve("plain-default"));
    Path exported = temp.resolve("exported.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", exported.toString()))
        .isZero();
    assertThat(Files.getPosixFilePermissions(exported))
        .isEqualTo(Files.getPosixFilePermissions(plain));
  }

  @Test
  void tableImportRefusesACellThatCouldNeverBeLoadedFromAFile() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    String exported = Files.readString(csv, UTF_8);
    assertThat(exported).contains("2018-01-03,,,\n");
    // a set value over the scalar cap of the pack format: the importer refuses it with a coded
    // problem (before the writer is tried, which would refuse it too)
    Files.writeString(
        csv,
        exported.replaceFirst("2018-01-03,,,\n", "2018-01-03,,k=" + "v".repeat(9_000) + ",\n"),
        UTF_8);
    Map<String, String> before = snapshot(pack);
    outBytes.reset();
    errBytes.reset();

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isEqualTo(1);

    assertThat(stderr()).contains("problem CSV_CELL_TOO_LONG " + SCOPE);
    assertThat(stderr()).endsWith("nothing written\n");
    assertThat(snapshot(pack)).isEqualTo(before);
  }

  @Test
  void tableImportOfAnEditedCellChangesExactlyOneRow() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    String edited =
        Files.readString(csv, UTF_8)
            .replace("client.kind == 'NATURAL_PERSON'", "client.kind == 'LEGAL_ENTITY'");
    Files.writeString(csv, edited, UTF_8);
    List<String> oldLines = text(SCOPE).lines().toList();
    outBytes.reset();

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isZero();

    assertThat(stdoutLines()).contains("rows +0 -0 ~1");
    assertThat(stdout()).contains("pack seal");
    List<String> newLines = text(SCOPE).lines().toList();
    assertThat(newLines).hasSameSizeAs(oldLines);
    List<String> changed = new ArrayList<>();
    for (int i = 0; i < oldLines.size(); i++) {
      if (!oldLines.get(i).equals(newLines.get(i))) {
        changed.add(newLines.get(i));
      }
    }
    assertThat(changed).containsExactly("    when: \"client.kind == 'LEGAL_ENTITY'\"");
    // the checksums are stale until the author seals again
    outBytes.reset();
    assertThat(run("pack", "test", pack.toString())).isEqualTo(1);
    assertThat(run("pack", "seal", pack.toString())).isZero();
    outBytes.reset();
    // the edited rule now matches legal entities, so the sealed pack fails its golden cases
    assertThat(run("pack", "test", pack.toString())).isEqualTo(1);
    assertThat(stdout()).contains("GOLDEN_CASE_FAILED");
  }

  @Test
  void tableImportCountsAddedAndRemovedRows() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    // a renamed row is one row gone and one row new
    Files.writeString(
        csv, Files.readString(csv, UTF_8).replace("S-N03-novation", "S-N03-renamed"), UTF_8);
    outBytes.reset();

    int exit = run("pack", "table", "import", pack.toString(), SCOPE, csv.toString());

    assertThat(exit).isZero();
    assertThat(stdoutLines()).contains("rows +1 -1 ~0");
  }

  @Test
  void tableImportWithoutWriteChangesNothing() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    Files.writeString(
        csv,
        Files.readString(csv, UTF_8)
            .replace("client.kind == 'NATURAL_PERSON'", "client.kind == 'LEGAL_ENTITY'"),
        UTF_8);
    Map<String, String> before = snapshot(pack);
    outBytes.reset();

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString())).isZero();

    assertThat(stdoutLines()).contains("rows +0 -0 ~1");
    assertThat(stdout()).contains("nothing written");
    assertThat(snapshot(pack)).isEqualTo(before);
  }

  @Test
  void tableImportRejectsAPathOutsideTheScopeTables() throws IOException {
    Path csv = temp.resolve("table.csv");
    Files.writeString(csv, "id\n", UTF_8);
    Map<String, String> before = snapshot(pack);
    for (String path :
        List.of(
            "scope/other.yaml",
            "tests/authorship.yaml",
            "model/canonical-input.yaml",
            "../pack/" + SCOPE,
            "/" + SCOPE,
            "scope\\sample-scope.yaml",
            "")) {
      errBytes.reset();
      assertThat(run("pack", "table", "import", pack.toString(), path, csv.toString(), "--write"))
          .as(path)
          .isEqualTo(2);
      assertThat(stderr()).as(path).contains("TABLE_PATH_INVALID");
      errBytes.reset();
      assertThat(run("pack", "table", "export", pack.toString(), path)).as(path).isEqualTo(2);
      assertThat(stderr()).as(path).contains("TABLE_PATH_INVALID");
    }
    assertThat(stdout()).isEmpty();
    assertThat(snapshot(pack)).isEqualTo(before);
  }

  @Test
  void tableImportWithProblemsWritesNothingEvenWithWrite() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    String exported = Files.readString(csv, UTF_8);
    Map<String, String> before = snapshot(pack);
    String natural = "client.kind == 'NATURAL_PERSON'";
    // case name, edited CSV, the problem code the command must print
    List<String[]> edits =
        List.of(
            new String[] {
              "a duplicate priority",
              exported.replace("S-N08-equity-on-venue,N08,800,", "S-N08-equity-on-venue,N08,400,"),
              "PRIORITY_DUPLICATE"
            },
            new String[] {
              "a row with an extra cell",
              exported.replace("SAMPLE.N03.EXCLUDED,", "SAMPLE.N03.EXCLUDED,,"),
              "CSV_CELL_COUNT"
            },
            new String[] {
              "an unknown reason",
              exported.replace("SAMPLE.N03.EXCLUDED", "SAMPLE.N03.NOT_DECLARED"),
              "ROW_REASON_UNKNOWN"
            },
            new String[] {
              "an expression that does not parse",
              exported.replace(natural, "client.kind == ["),
              "WHEN_DOES_NOT_COMPILE"
            },
            new String[] {
              "an expression over an undeclared variable",
              exported.replace(natural, "client.nothing == 'X'"),
              "WHEN_DOES_NOT_COMPILE"
            },
            new String[] {"a blank line", exported + "\n", "CSV_CELL_COUNT"});

    for (String[] edit : edits) {
      Files.writeString(csv, edit[1], UTF_8);
      outBytes.reset();
      errBytes.reset();
      assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
          .as(edit[0])
          .isEqualTo(1);
      assertThat(stderr()).as(edit[0]).contains("problem " + edit[2] + " " + SCOPE);
      assertThat(stderr()).as(edit[0]).endsWith("nothing written\n");
      assertThat(stdout()).as(edit[0]).doesNotContain("rows +");
      assertThat(snapshot(pack)).as(edit[0]).isEqualTo(before);
    }
  }

  @Test
  void tableImportRefusesACsvOverTheFileSizeCap() throws IOException {
    Path csv = temp.resolve("huge.csv");
    Files.write(csv, new byte[PackFormat.MAX_FILE_BYTES + 1]);
    Map<String, String> before = snapshot(pack);

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isEqualTo(2);

    assertThat(stderr()).contains("larger than " + PackFormat.MAX_FILE_BYTES + " bytes");
    assertThat(snapshot(pack)).isEqualTo(before);
    try (Stream<Path> scope = Files.list(pack.resolve("scope"))) {
      assertThat(scope.count()).isEqualTo(1);
    }
  }

  @Test
  void tableImportRefusesACsvThatIsNotUtf8() throws IOException {
    Path csv = temp.resolve("latin1.csv");
    Files.write(csv, new byte[] {'i', 'd', ',', (byte) 0xE9, '\n'});

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isEqualTo(2);

    assertThat(stderr()).contains("UTF-8");
  }

  @Test
  void aPackThatIsReadBackStillLoadsAsASourceAfterAnImport() throws IOException {
    Path csv = temp.resolve("table.csv");
    assertThat(run("pack", "table", "export", pack.toString(), SCOPE, "--out", csv.toString()))
        .isZero();
    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, csv.toString(), "--write"))
        .isZero();

    // no temporary file is left in the pack directory
    assertThat(PackSource.fromDirectory(pack).paths())
        .contains(SCOPE)
        .noneMatch(p -> p.contains("tmp"));
  }

  // ---- hostile input: the number of problem lines is bounded (final review APP-2)
  // -----------------

  /** Problem lines the commands may print: the load cap, its marker and the summary lines. */
  private static final int MAX_LINES = 1_100;

  private static final String HOSTILE_HEAD =
      "table: {id: sample.s0.scope, hit_policy: FIRST, priority: explicit, inputs: [client.kind],"
          + " catch_all: S-DEFAULT-catch-all}\nrows:\n";

  private void hostileTable(String row, int rows) throws IOException {
    StringBuilder text = new StringBuilder(HOSTILE_HEAD);
    for (int i = 1; i <= rows; i++) {
      text.append(row.replace("@N@", Integer.toString(i))).append('\n');
    }
    write(SCOPE, text.toString());
    Seal.seal(pack);
  }

  @Test
  void testOnATableOfFortyNineThousandBadRowsPrintsAtMostTheCappedList() throws IOException {
    hostileTable("  - {id: R1}", 49_000);

    assertThat(run("pack", "test", pack.toString())).isEqualTo(1);

    List<String> lines = stdoutLines();
    assertThat(lines.size()).isLessThanOrEqualTo(MAX_LINES);
    assertThat(lines).anyMatch(l -> l.startsWith("problem PROBLEMS_TRUNCATED"));
    assertThat(last(lines)).startsWith("pack test: FAIL (");
  }

  @Test
  void testOnATableWhoseEveryRowFailsToCompilePrintsAtMostTheCappedList() throws IOException {
    hostileTable(
        "  - {id: R@N@, node: N03, priority: @N@, when: \"no.such == 1\", outcome: DO_NOT_REPORT,"
            + " reason: SAMPLE.N03.EXCLUDED, rationale: RAT-0001, valid: {legal: {from: 2018-01-03}},"
            + " cite: [{source_id: sample-law, provision_path: sample/1, role: BASIS}]}",
        3_000);
    String catchAll = text("scope/sample-scope.yaml");
    write(
        SCOPE,
        catchAll
            + "  - {id: S-DEFAULT-catch-all, node: DEFAULT, priority: 9999, when: \"true\","
            + " outcome: UNDETERMINED, reason: SAMPLE.DEFAULT.NO_RULE_HIT, rationale: RAT-0004,"
            + " cite: [{source_id: regpack-spec, provision_path: I5, role: GUIDANCE}]}\n");
    Seal.seal(pack);

    assertThat(run("pack", "test", pack.toString())).isEqualTo(1);

    List<String> lines = stdoutLines();
    assertThat(lines.size()).isLessThanOrEqualTo(MAX_LINES);
    assertThat(lines).anyMatch(l -> l.startsWith("problem PROBLEMS_TRUNCATED"));
  }

  @Test
  void theLoadFailurePathPrintsAtMostTheCappedList() throws IOException {
    hostileTable("  - {id: R1}", 49_000);

    assertThat(run("pack", "explain", pack.toString(), "--rule", "RULE-0001")).isEqualTo(1);

    List<String> lines = stderr().lines().toList();
    assertThat(lines.size()).isLessThanOrEqualTo(MAX_LINES);
    assertThat(lines).anyMatch(l -> l.startsWith("problem PROBLEMS_TRUNCATED"));
  }

  @Test
  void tableImportOfAHalfMillionLineCsvPrintsAtMostTheCappedList() throws IOException {
    StringBuilder csv =
        new StringBuilder(
            "id,node,priority,when,outcome,reason,rule_ref,cite,rationale,valid_from,valid_to,set,"
                + "legs\n");
    for (int i = 0; i < 520_000; i++) {
      csv.append("x\n");
    }
    Path file = temp.resolve("many.csv");
    Files.writeString(file, csv.toString(), UTF_8);

    assertThat(run("pack", "table", "import", pack.toString(), SCOPE, file.toString()))
        .isEqualTo(1);

    List<String> lines = stderr().lines().toList();
    assertThat(lines.size()).isLessThanOrEqualTo(105);
    assertThat(lines).anyMatch(l -> l.startsWith("problem PROBLEMS_TRUNCATED"));
    assertThat(last(lines)).startsWith("pack table import: FAIL (101 problems)");
  }
}
