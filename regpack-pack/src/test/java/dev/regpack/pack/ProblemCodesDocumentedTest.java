package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps section 6 of {@code docs/pack-format/p2a-format-notes.md} complete (plan T26: the notes
 * list every problem code with one line): every upper-case code string of the main sources of the
 * pack, engine and application modules is in the table or in the short list of strings that are not
 * problem codes, and every code of the table still exists in a source.
 */
class ProblemCodesDocumentedTest {
  private static final Pattern TOKEN =
      Pattern.compile("\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)(?=[\" ])");
  private static final Pattern TABLE_CODE =
      Pattern.compile("^\\| `([A-Z][A-Z0-9_]*)` \\| (.+) \\|$");

  /** Quoted strings in the sources that are not problem codes. */
  private static final Set<String> NOT_PROBLEM_CODES =
      Set.of(
          // rounding modes, ruling values, an enum name of the skeleton regime
          "HALF_UP",
          "HALF_EVEN",
          "ERROR_IF_ANCESTOR_ABSENT",
          "BY_NAME",
          "NATURAL_PERSON",
          // decision reason codes of the engine (section 6.11 of the notes)
          "EXEC_ERROR",
          "EXEC_BUDGET",
          "EXEC_CIRCUIT_OPEN",
          "ENRICH_MISS",
          // a provenance kind name, and a word of the explain output: neither is a code
          "CONSISTENCY_ONLY",
          "NO_PROVENANCE");

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static Set<String> sourceTokens() {
    Set<String> tokens = new TreeSet<>();
    for (String module : List.of("regpack-pack", "regpack-engine", "regpack-app")) {
      Path root = repo().resolve(module).resolve("src/main/java");
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
          Matcher m = TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
          while (m.find()) {
            tokens.add(m.group(1));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    tokens.removeIf(t -> t.startsWith("REGPACK_"));
    return tokens;
  }

  /** The codes of the table rows between the headings of sections 6 and 7. */
  static List<String> documentedCodes(String notes) {
    int from = notes.indexOf("\n## 6. ");
    int to = notes.indexOf("\n## 7. ");
    assertThat(from).as("section 6 heading").isPositive();
    assertThat(to).as("section 7 heading").isGreaterThan(from);
    List<String> codes = new ArrayList<>();
    for (String line : notes.substring(from, to).split("\n")) {
      Matcher m = TABLE_CODE.matcher(line.strip());
      if (m.matches()) {
        assertThat(m.group(2)).as("meaning of %s", m.group(1)).isNotBlank();
        codes.add(m.group(1));
      }
    }
    return codes;
  }

  @Test
  void everyProblemCodeOfTheSourcesIsInTheFormatNotesAndNoStaleCodeIsListed() throws IOException {
    String notes = Files.readString(repo().resolve("docs/pack-format/p2a-format-notes.md"));
    List<String> documented = documentedCodes(notes);
    assertThat(documented).as("a code listed twice").doesNotHaveDuplicates();
    Set<String> tokens = sourceTokens();

    Set<String> missing = new TreeSet<>(tokens);
    missing.removeAll(documented);
    missing.removeAll(NOT_PROBLEM_CODES);
    assertThat(missing).as("codes in the sources but not in section 6 of the notes").isEmpty();

    Set<String> stale = new TreeSet<>(documented);
    stale.removeAll(tokens);
    assertThat(stale).as("codes in the notes but in no source").isEmpty();
  }

  @Test
  void theCodeTableReaderFindsRowsAndRefusesAMissingSection() {
    String notes =
        "intro\n## 6. Problem codes\n| code | meaning |\n|---|---|\n| `A_B` | one |\n| `C_D` | two |\n"
            + "## 7. Next\n| `E_F` | outside |\n";
    assertThat(documentedCodes(notes)).containsExactly("A_B", "C_D");
  }
}
