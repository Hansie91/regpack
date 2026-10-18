package dev.regpack.engine.expr.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.engine.expr.Rulings;
import dev.regpack.engine.expr.Rulings.AndLeftError;
import dev.regpack.engine.expr.Rulings.HasBelowAbsent;
import dev.regpack.engine.expr.Rulings.IntDecimalMix;
import dev.regpack.engine.expr.Rulings.OrLeftError;
import dev.regpack.engine.expr.conformance.RulingsFile.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code rulings.tsv}: the one file that holds the five DECIDE rulings (assumption A2, OI-5). */
class RulingsFileTest {
  private static final String TEXT = RulingsFile.text();

  private static void refused(String text, String fragment) {
    assertThatThrownBy(() -> Rulings.parse(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(fragment);
  }

  @Test
  void loadsFiveProvisionalRows() {
    List<Row> rows = RulingsFile.load();
    assertThat(rows).extracting(Row::id).containsExactly("D13", "A04", "E06", "A07", "S07");
    assertThat(rows).extracting(Row::status).containsOnly("PROVISIONAL");
    assertThat(rows)
        .extracting(Row::switchName)
        .containsExactly(
            "intDecimalMix", "andLeftError", "orLeftError", "hasBelowAbsent", "enumStringEquality");
    assertThat(rows)
        .extracting(Row::value, Row::altValue, Row::expected, Row::expectedAlt)
        .containsExactly(
            tuple("ALLOW", "DENY", "T", "ERR"),
            tuple("PROPAGATE", "ABSORB", "ERR", "F"),
            tuple("PROPAGATE", "ABSORB", "ERR", "T"),
            tuple("FALSE", "ERROR_IF_ANCESTOR_ABSENT", "F", "ERR"),
            tuple("BY_NAME", "-", "T", "-"));
    // L01 stays visible as a note: it is not a vector
    assertThat(TEXT).contains("# L01 NOT_A_VECTOR spec 6.2: no loops");
    // exactly one file, LF only, no tab inside a note
    assertThat(TEXT).doesNotContain("\r");
    assertThat(Rulings.parse(TEXT)).isNotNull();
  }

  @Test
  void provisionalRowsHaveNoRulingMetadataAndRuledRowsHaveBoth() {
    for (Row row : RulingsFile.load()) {
      assertThat(row.ruledBy()).as(row.id()).isEqualTo("-");
      assertThat(row.ruledOn()).as(row.id()).isEqualTo("-");
    }
    // a provisional row with metadata is refused
    refused(RulingsFile.replaceCell(TEXT, "D13", 7, "owner"), "PROVISIONAL");
    refused(RulingsFile.replaceCell(TEXT, "D13", 8, "2026-10-20"), "PROVISIONAL");
    // a ruled row needs both
    String ruled = RulingsFile.replaceCell(TEXT, "D13", 1, "RULED");
    refused(ruled, "RULED");
    String half = RulingsFile.replaceCell(ruled, "D13", 7, "owner");
    refused(half, "RULED");
    String full = RulingsFile.replaceCell(half, "D13", 8, "2026-10-20");
    assertThat(Rulings.parse(full)).isEqualTo(Rulings.provisional());
    // the one-line ruling: value alone changes the switch
    Rulings deny = Rulings.parse(RulingsFile.withValue(full, "D13", "DENY"));
    assertThat(deny.intDecimalMix()).isEqualTo(IntDecimalMix.DENY);
    assertThat(deny.andLeftError()).isEqualTo(AndLeftError.PROPAGATE);
    // a date that is not a date is refused
    refused(RulingsFile.replaceCell(full, "D13", 8, "2026-02-30"), "ruled_on");
    refused(RulingsFile.replaceCell(full, "D13", 8, "soon"), "ruled_on");
    // an unknown status is refused
    refused(RulingsFile.replaceCell(TEXT, "D13", 1, "MAYBE"), "status");
  }

  @Test
  void unknownSwitchOrValueIsRefused() {
    refused(RulingsFile.replaceCell(TEXT, "D13", 2, "intDecimalMixx"), "switch");
    refused(RulingsFile.replaceCell(TEXT, "D13", 3, "MAYBE"), "value");
    refused(RulingsFile.replaceCell(TEXT, "A04", 3, "ALLOW"), "value");
    refused(RulingsFile.replaceCell(TEXT, "A07", 3, "propagate"), "value");
    // S07: BY_NAME only; a TYPE_ERROR ruling is a plan amendment, not a value
    refused(RulingsFile.replaceCell(TEXT, "S07", 3, "TYPE_ERROR"), "S07");
    refused(RulingsFile.replaceCell(TEXT, "S07", 3, "BY_CODE"), "S07");
    refused(RulingsFile.replaceCell(TEXT, "S07", 4, "TYPE_ERROR"), "S07");
    // the alternative column must name the alternative of the switch
    refused(RulingsFile.replaceCell(TEXT, "D13", 4, "ALLOW"), "alt_value");
    // a row missing, duplicated, unknown or with a wrong cell count
    refused(TEXT.replaceAll("(?m)^S07\t.*\n", ""), "S07");
    refused(
        TEXT + "D13\tPROVISIONAL\tintDecimalMix\tALLOW\tDENY\tT\tERR\t-\t-\tagain\n", "duplicate");
    refused(TEXT + "X99\tPROVISIONAL\tintDecimalMix\tALLOW\tDENY\tT\tERR\t-\t-\tx\n", "X99");
    refused(RulingsFile.replaceCell(TEXT, "D13", 9, "a\tb"), "cells");
    refused(TEXT.replace("id\tstatus", "id\tstate"), "header");
    refused("", "header");
    // an expectation cell may not be empty
    refused(RulingsFile.replaceCell(TEXT, "D13", 5, ""), "expected");
  }

  @Test
  void loadedRulingsEqualProvisional() {
    Rulings loaded = Rulings.load();
    assertThat(loaded).isEqualTo(Rulings.provisional());
    assertThat(loaded.intDecimalMix()).isEqualTo(IntDecimalMix.ALLOW);
    assertThat(loaded.andLeftError()).isEqualTo(AndLeftError.PROPAGATE);
    assertThat(loaded.orLeftError()).isEqualTo(OrLeftError.PROPAGATE);
    assertThat(loaded.hasBelowAbsent()).isEqualTo(HasBelowAbsent.FALSE);
    // CRLF checkouts parse the same
    assertThat(Rulings.parse(TEXT.replace("\n", "\r\n"))).isEqualTo(loaded);
  }
}
