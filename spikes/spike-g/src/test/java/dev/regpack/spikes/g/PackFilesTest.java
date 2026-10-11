package dev.regpack.spikes.g;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Reads the SP-G documents under {@code docs/spikes/sp-g/}: the paper exercise is only evidence if
 * its files parse, keep the layout of spec 6.1, label every example, and register every place where
 * the format could not say what EMIR needs (review focus RF7, RF18).
 */
class PackFilesTest {
  private static final Set<String> SIX = Set.of("NEWT", "MODI", "TERM", "EROR", "REVI", "VALU");
  private static final Pattern MARKER = Pattern.compile("#\\s*BRK-(\\d{2})");
  private static final int MIN_OLD = 20;
  private static final Set<String> GOLDEN_KEYS =
      Set.of("golden", "golden_cases", "cases", "fixtures");
  // a section token (paragraph sign and number), an OLD quotation or a NEW wording, in file order;
  // a section token inside an OLD or NEW text is consumed by that quotation and is not a token
  private static final Pattern FIX_TOKEN =
      Pattern.compile("§(\\d+(?:\\.\\d+)*)|OLD<<(.*?)>>|NEW<<(.*?)>>", Pattern.DOTALL);

  private static final Set<String> SPEC_61_FOLDERS =
      Set.of("model", "scope", "route", "derive", "enrich", "validate", "endpoints", "tests");
  private static final Set<String> SPEC_61_TOP_LEVEL =
      Set.of(
          "pack.yaml",
          "lifecycle.yaml",
          "refdata.yaml",
          "reasons.yaml",
          "sources.yaml",
          "rationales.yaml",
          "interpretations.yaml",
          "CHECKSUMS",
          "ATTESTATION");
  private static final List<String> PACK_FILES =
      List.of(
          "derive/action-type.yaml",
          "endpoints/tr-sftp.yaml",
          "lifecycle.yaml",
          "model/canonical-extension.yaml",
          "pack.yaml",
          "validate/sequencing.yaml");

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static Path sp() {
    return repo().resolve("docs/spikes/sp-g");
  }

  private static Path pack() {
    return sp().resolve("emir-pack");
  }

  private static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    assertThat(value).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    assertThat(value).isInstanceOf(List.class);
    return (List<Object>) value;
  }

  private static Map<String, Object> yaml(String relative) throws IOException {
    return map(MiniYaml.parse(read(pack().resolve(relative))));
  }

  private static List<Register.Row> register() throws IOException {
    return Register.parse(read(sp().resolve("register.md")));
  }

  private static List<String> packFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(pack())) {
      return walk.filter(Files::isRegularFile)
          .map(p -> pack().relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }

  private static List<Path> allFilesUnderSpG() throws IOException {
    try (Stream<Path> walk = Files.walk(sp())) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    }
  }

  @Test
  void fileLayoutMatchesSpec61() throws IOException {
    assertThat(packFiles()).containsExactlyElementsOf(PACK_FILES);
    for (String file : packFiles()) {
      int slash = file.indexOf('/');
      if (slash < 0) {
        assertThat(SPEC_61_TOP_LEVEL).as("top-level file %s", file).contains(file);
      } else {
        assertThat(SPEC_61_FOLDERS).as("folder of %s", file).contains(file.substring(0, slash));
      }
    }
    assertThat(Files.isRegularFile(sp().resolve("README.md"))).isTrue();
    assertThat(Files.isRegularFile(sp().resolve("register.md"))).isTrue();

    // every pack file parses with the subset parser and is labelled as a whole
    for (String file : PACK_FILES) {
      assertThat(yaml(file)).as(file).containsEntry("provenance", "consistency-only");
    }

    // the pack.yaml fields that spec 6.1 lists
    assertThat(yaml("pack.yaml").keySet())
        .contains(
            "id",
            "semver",
            "regime",
            "jurisdiction",
            "valid",
            "as_at_field",
            "engine_min",
            "input_schema",
            "supersedes",
            "reports");
    assertThat(yaml("pack.yaml")).containsEntry("id", "emir.eu");
    assertThat(map(yaml("pack.yaml").get("valid"))).containsKey("legal");

    // the lifecycle.yaml content that spec 6.1 lists, plus the key that research 3.10 adds
    assertThat(yaml("lifecycle.yaml").keySet())
        .contains("business_key", "record_key", "successor_gate", "due_at");
  }

  @Test
  void allSixActionTypesAppearInDeriveAndSequencing() throws IOException {
    Set<String> derived = new TreeSet<>();
    for (Object row : list(yaml("derive/action-type.yaml").get("rows"))) {
      Object value = map(row).get("value");
      if (value != null) {
        derived.add((String) value);
      }
    }
    assertThat(derived).containsAll(SIX);

    Set<String> sequenced = new TreeSet<>();
    List<String> ids = new ArrayList<>();
    for (Object rule : list(yaml("validate/sequencing.yaml").get("rules"))) {
      Map<String, Object> r = map(rule);
      ids.add((String) r.get("id"));
      sequenced.add((String) r.get("action"));
      assertThat(r).containsEntry("layer", "L5").containsEntry("predictability", "partial");
    }
    assertThat(sequenced).containsAll(SIX);
    assertThat(ids)
        .containsExactly(
            "SYN-EMIR-SEQ-01",
            "SYN-EMIR-SEQ-02",
            "SYN-EMIR-SEQ-03",
            "SYN-EMIR-SEQ-04",
            "SYN-EMIR-SEQ-05",
            "SYN-EMIR-SEQ-06");
  }

  @Test
  void everyBreakageMarkerHasARegisterRow() throws IOException {
    Set<String> markers = new TreeSet<>();
    for (String file : PACK_FILES) {
      Matcher m = MARKER.matcher(read(pack().resolve(file)));
      while (m.find()) {
        markers.add("BRK-" + m.group(1));
      }
    }
    Set<String> registered = new TreeSet<>();
    for (Register.Row row : register()) {
      registered.add(row.id());
    }
    assertThat(registered).isNotEmpty();
    // equal sets: no marker without a row (a construct invented in silence) and no row without a
    // marker (a register row that points at nothing); Register.parse already rules out gaps
    assertThat(markers).containsExactlyElementsOf(registered);
  }

  @Test
  void registerHoldsAllTenHypothesesInOrder() throws IOException {
    List<String> hypotheses = register().stream().map(Register.Row::hypothesis).toList();
    assertThat(hypotheses.subList(0, 10))
        .containsExactly("H1", "H2", "H3", "H4", "H5", "H6", "H7", "H8", "H9", "H10");
    // a hypothesis may have a second row for a half that the first row does not cover (H1: BRK-17)
    assertThat(hypotheses.subList(10, hypotheses.size()))
        .allMatch(h -> h.equals("new") || h.matches("H([1-9]|10)"));
  }

  @Test
  void everyUndispositionedFixQuotesTheSpecVerbatim() throws IOException {
    String spec = spec();
    for (Register.Row row : register()) {
      String fix = row.proposedFix();
      if (row.status().equals("PROPOSED")) {
        assertThat(fixProblems(fix, spec)).as("%s proposed_fix", row.id()).isEmpty();
      } else if (row.status().equals("OPEN")) {
        // an OPEN row has no fix: it is the honest state of a refuted hypothesis or a missing fix
        assertThat(fix).as("%s has no fix text", row.id()).doesNotContain("OLD<<");
      }
    }
  }

  @Test
  void theFixCheckRejectsShortWrongSectionUnnamedAndUnchangedQuotations() {
    String spec =
        "## 1. One\nalpha beta gamma delta epsilon zeta eta theta\n### 1.1 Sub\n"
            + "unique sentence of the subsection number one\n## 2. Two\n"
            + "other wording of the second section appears here\n";
    String second = "other wording of the second section";
    // a well-formed fix passes
    assertThat(fixProblems("§2 OLD<<" + second + ">> NEW<<changed wording of two>>", spec))
        .isEmpty();
    // a one-word quotation is not a quotation
    assertThat(fixProblems("§1 OLD<<alpha>> NEW<<something else entirely different>>", spec))
        .anyMatch(p -> p.contains("shorter"));
    // the text exists in the spec, but in the second section and not in the section the row names
    assertThat(fixProblems("§1 OLD<<" + second + ">> NEW<<changed wording of two>>", spec))
        .anyMatch(p -> p.contains("not in section 1"));
    // the text of a subsection is not the text of its parent section
    String sub = "unique sentence of the subsection";
    assertThat(fixProblems("§1 OLD<<" + sub + ">> NEW<<changed wording of one>>", spec))
        .anyMatch(p -> p.contains("not in section 1"));
    // a quotation with no section token before it
    assertThat(fixProblems("OLD<<" + second + ">> NEW<<changed wording of two>>", spec))
        .anyMatch(p -> p.contains("names no section"));
    // a section that the spec does not have
    assertThat(fixProblems("§7 OLD<<" + second + ">> NEW<<changed wording of two>>", spec))
        .anyMatch(p -> p.contains("no section 7"));
    // a new wording equal to the old one changes nothing
    assertThat(fixProblems("§2 OLD<<" + second + ">> NEW<<" + second + ">>", spec))
        .anyMatch(p -> p.contains("equals"));
    // a section token inside NEW is not the section of the next quotation
    assertThat(
            fixProblems(
                "§2 OLD<<"
                    + second
                    + ">> NEW<<see §1 for more>> ; OLD<<alpha beta gamma delta epsilon>>"
                    + " NEW<<changed wording of one>>",
                spec))
        .anyMatch(p -> p.contains("not in section 2"));
  }

  private static String spec() throws IOException {
    return read(
        repo()
            .resolve("docs/superpowers/specs/2026-10-08-regulatory-reporting-platform-design.md"));
  }

  /**
   * The problems of one proposed fix: each OLD quotation must follow a section token (a paragraph
   * sign and a number), have at least {@link #MIN_OLD} characters, differ from its NEW wording and
   * lie between the heading of that section and the next heading of level two or three.
   */
  static List<String> fixProblems(String fix, String spec) {
    List<String> problems = new ArrayList<>();
    Matcher m = FIX_TOKEN.matcher(fix);
    String section = null;
    String pendingOld = null;
    int olds = 0;
    int news = 0;
    while (m.find()) {
      if (m.group(1) != null) {
        section = m.group(1);
      } else if (m.group(2) != null) {
        olds++;
        String quoted = m.group(2);
        pendingOld = quoted;
        if (quoted.length() < MIN_OLD) {
          problems.add("old wording is shorter than " + MIN_OLD + " characters: " + quoted);
        }
        if (section == null) {
          problems.add("old wording names no section: " + quoted);
        } else {
          String body = sectionBody(spec, section);
          if (body == null) {
            problems.add("the spec has no section " + section);
          } else if (!body.contains(quoted)) {
            problems.add("old wording is not in section " + section + ": " + quoted);
          }
        }
      } else {
        news++;
        if (m.group(3).isBlank()) {
          problems.add("empty new wording");
        }
        if (m.group(3).equals(pendingOld)) {
          problems.add("new wording equals the old wording: " + pendingOld);
        }
      }
    }
    if (olds == 0) {
      problems.add("no OLD quotation");
    }
    if (olds != news) {
      problems.add("OLD and NEW quotations differ in number: " + olds + " and " + news);
    }
    return problems;
  }

  /** The text from the heading of a numbered section to the next level-two or -three heading. */
  static String sectionBody(String spec, String number) {
    // "10" is a level-two heading "## 10. Title"; "9.2" a level-three heading "### 9.2 Title"
    Pattern heading =
        Pattern.compile("^#{2,3} " + Pattern.quote(number) + (number.contains(".") ? " " : "\\. "));
    StringBuilder body = null;
    for (String line : spec.split("\r?\n", -1)) {
      if (body == null) {
        if (heading.matcher(line).find()) {
          body = new StringBuilder();
        }
      } else if (line.startsWith("## ") || line.startsWith("### ")) {
        break;
      } else {
        body.append(line).append('\n');
      }
    }
    return body == null ? null : body.toString();
  }

  @Test
  void everyProposedRowHasAMarkerOnALineWithContent() throws IOException {
    Set<String> inline = new TreeSet<>();
    for (String file : PACK_FILES) {
      inline.addAll(inlineMarkers(read(pack().resolve(file))));
    }
    for (Register.Row row : register()) {
      if (row.status().equals("PROPOSED")) {
        // a marker that sits in a prose comment points at no encoded line: the hypothesis would be
        // "confirmed" without an encoding that exposes it
        assertThat(inline).as("%s has an inline marker", row.id()).contains(row.id());
      }
    }
  }

  @Test
  void aMarkerInACommentOnlyLineIsNotInline() {
    String text =
        "# BRK-05 prose about a refuted hypothesis\n  # BRK-07 indented prose\n"
            + "key: value   # BRK-06 (block)\n- {a: b}   #BRK-08\n";
    assertThat(inlineMarkers(text)).containsExactlyInAnyOrder("BRK-06", "BRK-08");
  }

  /**
   * The ids of the markers that follow text on their line; a marker on a comment-only line is not.
   */
  static Set<String> inlineMarkers(String text) {
    Set<String> ids = new TreeSet<>();
    for (String line : text.split("\r?\n", -1)) {
      Matcher m = MARKER.matcher(line);
      while (m.find()) {
        if (!line.substring(0, m.start()).isBlank()) {
          ids.add("BRK-" + m.group(1));
        }
      }
    }
    return ids;
  }

  @Test
  void everyDecisionTableHasUniqueIdsAndACatchAll() throws IOException {
    Map<String, Object> table = yaml("derive/action-type.yaml");
    assertThat(map(table.get("table"))).containsEntry("hit_policy", "FIRST");
    List<Object> rows = list(table.get("rows"));
    assertThat(rows).isNotEmpty();

    Set<String> ids = new TreeSet<>();
    Set<Integer> priorities = new TreeSet<>();
    int previous = Integer.MIN_VALUE;
    int catchAlls = 0;
    for (Object each : rows) {
      Map<String, Object> row = map(each);
      assertThat(ids.add((String) row.get("id"))).as("unique id %s", row.get("id")).isTrue();
      int priority = Integer.parseInt((String) row.get("priority"));
      assertThat(priorities.add(priority)).as("unique priority %s", priority).isTrue();
      assertThat(priority).as("rows are listed by priority").isGreaterThan(previous);
      previous = priority;
      assertThat(row.keySet())
          .as("row shape of spec 6.1 plus the rule_ref that spec 6.1 asks of derive rules")
          .contains(
              "id", "node", "priority", "when", "outcome", "reason", "cite", "valid", "rule_ref");
      if ("true".equals(row.get("when"))) {
        catchAlls++;
        assertThat(row).containsEntry("outcome", "UNDETERMINED");
      }
    }
    assertThat(catchAlls).as("exactly one catch-all row").isEqualTo(1);
    Map<String, Object> last = map(rows.get(rows.size() - 1));
    assertThat(last).as("the last row is the catch-all").containsEntry("when", "true");
  }

  @Test
  void everyExampleIsLabelledConsistencyOnly() throws IOException {
    int found = 0;
    for (String file : PACK_FILES) {
      found += checkExamples(file, yaml(file));
      assertThat(provenanceProblems(yaml(file))).as("provenance in %s", file).isEmpty();
    }
    assertThat(found).isGreaterThanOrEqualTo(6);
    for (String file :
        List.of(
            "derive/action-type.yaml",
            "validate/sequencing.yaml",
            "model/canonical-extension.yaml")) {
      assertThat(checkExamples(file, yaml(file))).as("examples in %s", file).isPositive();
    }
  }

  @Test
  void theProvenanceCheckSeesUnlabelledBlocksUnderOtherKeys() {
    assertThat(
            provenanceProblems(MiniYaml.parse("examples:\n  - id: a\n    provenance: reviewed\n")))
        .anyMatch(p -> p.contains("reviewed"));
    assertThat(provenanceProblems(MiniYaml.parse("golden_cases:\n  - id: a\n")))
        .anyMatch(p -> p.contains("golden_cases"));
    assertThat(provenanceProblems(MiniYaml.parse("a:\n  Cases:\n    - id: a\n")))
        .anyMatch(p -> p.contains("Cases"));
    assertThat(provenanceProblems(MiniYaml.parse("fixtures: []\n")))
        .anyMatch(p -> p.contains("fixtures"));
    assertThat(provenanceProblems(MiniYaml.parse("checks:\n  - id: a\n    expect: {x: y}\n")))
        .anyMatch(p -> p.contains("expect"));
    assertThat(
            provenanceProblems(
                MiniYaml.parse(
                    "checks:\n  - id: a\n    provenance: consistency-only\n    expect: {x: y}\n")))
        .isEmpty();
  }

  /**
   * Walks every mapping: a key named golden, golden_cases, cases or fixtures (a block that would
   * hold reviewed cases), a {@code provenance} other than consistency-only, and a mapping with an
   * {@code expect} key but no provenance (an example under a key that is not {@code examples}).
   */
  @SuppressWarnings("unchecked")
  static List<String> provenanceProblems(Object node) {
    List<String> problems = new ArrayList<>();
    if (node instanceof Map<?, ?> m) {
      Map<String, Object> map = (Map<String, Object>) m;
      if (map.containsKey("expect") && !map.containsKey("provenance")) {
        problems.add("a mapping with an expect key has no provenance: " + map.keySet());
      }
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        String key = entry.getKey();
        if (GOLDEN_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
          problems.add("key " + key + " would hold golden cases, gate G1 has none");
        }
        if (key.equals("provenance") && !"consistency-only".equals(entry.getValue())) {
          problems.add("provenance is " + entry.getValue() + ", not consistency-only");
        }
        problems.addAll(provenanceProblems(entry.getValue()));
      }
    } else if (node instanceof List<?> l) {
      for (Object item : l) {
        problems.addAll(provenanceProblems(item));
      }
    }
    return problems;
  }

  @SuppressWarnings("unchecked")
  private static int checkExamples(String file, Object node) {
    int found = 0;
    if (node instanceof Map<?, ?> m) {
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) m).entrySet()) {
        if (entry.getKey().equals("examples")) {
          for (Object example : list(entry.getValue())) {
            assertThat(map(example))
                .as("example of %s", file)
                .containsEntry("provenance", "consistency-only");
            found++;
          }
        } else {
          found += checkExamples(file, entry.getValue());
        }
      }
    } else if (node instanceof List<?> l) {
      for (Object item : l) {
        found += checkExamples(file, item);
      }
    }
    return found;
  }

  @Test
  void noEsmaOrDrrIdentifiers() throws IOException {
    // gate G3 (no workbook text, no regulator rule ids) and gate G4 (no DRR artefact)
    List<String> forbidden = List.of("EMIR-VR-", "CON-", "DRR", "rosetta", "urn:iso");
    List<Path> files = allFilesUnderSpG();
    assertThat(files).isNotEmpty();
    for (Path file : files) {
      String text = read(file);
      String lower = text.toLowerCase(Locale.ROOT);
      for (String token : forbidden) {
        assertThat(text)
            .as("%s must not contain %s", file.getFileName(), token)
            .doesNotContain(token);
        assertThat(lower)
            .as("%s must not contain %s (any case)", file.getFileName(), token)
            .doesNotContain(token.toLowerCase(Locale.ROOT));
      }
      assertThat(file.getFileName().toString().toLowerCase(Locale.ROOT))
          .as("no schema or workbook file")
          .doesNotEndWith(".xsd")
          .doesNotEndWith(".xlsx")
          .doesNotEndWith(".xls");
    }
  }
}
