package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps the SP-G amendment table of the format notes complete (plan T26): each of the 15 accepted
 * wordings of {@code docs/spikes/sp-g/register.md} has exactly one row with a disposition in {@code
 * docs/pack-format/p2a-format-notes.md}, the notes cite no register id that the register lacks, and
 * BRK-18 (an accepted P12 item without a wording) is not offered as a wording.
 */
class AmendmentTableTest {
  private static final Pattern ROW_SPLIT = Pattern.compile("(?<!\\\\)\\|");
  private static final Pattern ID = Pattern.compile("BRK-[0-9]{2}");
  private static final Set<String> DISPOSITIONS = Set.of("IMPLEMENTED", "RESERVED", "LEFT");
  private static final String REGISTER_HEADER_ID = "id";

  private static Path repo() {
    return Path.of(System.getProperty("regpack.repo.root"));
  }

  private static String read(String relative) throws IOException {
    return Files.readString(repo().resolve(relative), StandardCharsets.UTF_8);
  }

  /** The cells of a table row, trimmed, without the empty cell before the first bar. */
  static List<String> cells(String line) {
    String[] parts = ROW_SPLIT.split(line.strip(), -1);
    List<String> cells = new ArrayList<>();
    for (int i = 1; i < parts.length - 1; i++) {
      cells.add(parts[i].strip());
    }
    return cells;
  }

  /** Register ids with status ACCEPTED and a proposed_fix other than "-" (the wordings). */
  static Set<String> acceptedWordings(String register) {
    Set<String> ids = new TreeSet<>();
    for (String line : register.split("\n")) {
      if (!line.startsWith("| BRK-")) {
        continue;
      }
      List<String> cells = cells(line);
      assertThat(cells)
          .as("register row %s", line.substring(0, Math.min(10, line.length())))
          .hasSize(10);
      // columns: id hypothesis location symptom emir_need proposed_fix impact severity status note
      if (cells.get(8).equals("ACCEPTED") && !cells.get(5).equals("-")) {
        ids.add(cells.get(0));
      }
    }
    return ids;
  }

  static Set<String> registerIds(String register) {
    Set<String> ids = new TreeSet<>();
    for (String line : register.split("\n")) {
      if (line.startsWith("| BRK-")) {
        ids.add(cells(line).get(0));
      }
    }
    return ids;
  }

  /** The disposition table of the notes: id to disposition (rows whose first cell is an id). */
  static Map<String, String> notesTable(String notes) {
    Map<String, String> table = new TreeMap<>();
    for (String line : notes.split("\n")) {
      if (!line.startsWith("| BRK-")) {
        continue;
      }
      List<String> cells = cells(line);
      String previous = table.put(cells.get(0), cells.size() > 1 ? cells.get(1) : "");
      if (previous != null) {
        table.put(cells.get(0), "DUPLICATE");
      }
    }
    return table;
  }

  /** Every complaint about the notes; empty when the table is complete and consistent. */
  static List<String> complaints(String register, String notes) {
    List<String> complaints = new ArrayList<>();
    Set<String> wordings = acceptedWordings(register);
    Set<String> known = registerIds(register);
    Map<String, String> table = notesTable(notes);
    if (wordings.size() != 15) {
      complaints.add("the register has " + wordings.size() + " accepted wordings, expected 15");
    }
    for (String id : wordings) {
      String disposition = table.get(id);
      if (disposition == null) {
        complaints.add(id + ": no row in the notes table");
      } else if (!DISPOSITIONS.contains(disposition)) {
        complaints.add(id + ": disposition '" + disposition + "' is not one of " + DISPOSITIONS);
      }
    }
    for (String id : table.keySet()) {
      if (!wordings.contains(id)) {
        complaints.add(id + ": the notes table offers it as a wording, the register does not");
      }
    }
    Matcher cited = ID.matcher(notes);
    while (cited.find()) {
      if (!known.contains(cited.group())) {
        complaints.add(cited.group() + ": cited by the notes, absent from the register");
      }
    }
    return complaints;
  }

  @Test
  void everyAcceptedWordingHasADisposition() throws IOException {
    String register = read("docs/spikes/sp-g/register.md");
    String notes = read("docs/pack-format/p2a-format-notes.md");
    assertThat(acceptedWordings(register)).hasSize(15);
    assertThat(acceptedWordings(register)).doesNotContain("BRK-18");
    assertThat(complaints(register, notes)).isEmpty();
    assertThat(notesTable(notes).keySet()).isEqualTo(acceptedWordings(register));
    assertThat(notesTable(notes)).doesNotContainKey("BRK-18");
  }

  // -- teeth: the check fails when the notes are wrong ------------------------------------------

  private static String id(int number) {
    return "BRK-" + (number < 10 ? "0" : "") + number;
  }

  private static String register() {
    StringBuilder b =
        new StringBuilder(
            "| "
                + REGISTER_HEADER_ID
                + " | h | l | s | e | proposed_fix | i | sev | status | n |\n");
    b.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (int i = 1; i <= 15; i++) {
      b.append("| " + id(i) + " | H | l | s | e | NEW<<a \\| b>> | i | S | ACCEPTED | n |\n");
    }
    b.append("| BRK-18 | H | l | s | e | - | i | S | ACCEPTED | a P12 item |\n");
    b.append("| BRK-19 | H | l | s | e | NEW<<x>> | i | S | REJECTED_NOT_NEEDED | n |\n");
    return b.toString();
  }

  private static String notes(int skip, String dispositionOfFirst) {
    StringBuilder b = new StringBuilder("| id | disposition | where |\n|---|---|---|\n");
    for (int i = 1; i <= 15; i++) {
      if (i == skip) {
        continue;
      }
      b.append("| " + id(i) + " | " + (i == 1 ? dispositionOfFirst : "LEFT") + " | somewhere |\n");
    }
    return b.toString();
  }

  @Test
  void aCompleteNotesTableIsAccepted() {
    assertThat(complaints(register(), notes(0, "IMPLEMENTED"))).isEmpty();
  }

  @Test
  void anEscapedBarInsideACellDoesNotShiftTheColumns() {
    assertThat(acceptedWordings(register())).hasSize(15).doesNotContain("BRK-18", "BRK-19");
  }

  @Test
  void aMissingRowIsReported() {
    assertThat(complaints(register(), notes(7, "LEFT")))
        .containsExactly("BRK-07: no row in the notes table");
  }

  @Test
  void anUnknownDispositionIsReported() {
    assertThat(complaints(register(), notes(0, "DONE")))
        .containsExactly("BRK-01: disposition 'DONE' is not one of " + DISPOSITIONS);
  }

  @Test
  void brk18OfferedAsAWordingIsReported() {
    String notes = notes(0, "LEFT") + "| BRK-18 | LEFT | a P12 item |\n";
    assertThat(complaints(register(), notes))
        .containsExactly("BRK-18: the notes table offers it as a wording, the register does not");
  }

  @Test
  void aCitedIdTheRegisterLacksIsReported() {
    String notes = notes(0, "LEFT") + "\nBRK-42 is mentioned in prose.\n";
    assertThat(complaints(register(), notes))
        .containsExactly("BRK-42: cited by the notes, absent from the register");
  }

  @Test
  void proseMayMentionRejectedAndP12ItemsThatTheRegisterHas() {
    String notes = notes(0, "LEFT") + "\nBRK-18 is a P12 item; BRK-19 is cited in prose.\n";
    assertThat(complaints(register(), notes)).isEmpty();
  }
}
