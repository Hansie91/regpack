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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                + " (gate G1: no named Compliance owner)",
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
    assertThat(last(stdoutLines())).startsWith("Tier 2: re-evaluated on the current engine");
    assertThat(stderr()).isEmpty();
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
}
