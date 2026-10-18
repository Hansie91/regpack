package dev.regpack.ports.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportLedgerRecordsTest {
  @Test
  void aVersionRequestWithoutABodyIsRejectedByName() {
    SubjectKey subject =
        new SubjectKey(
            new Regime("mifir.eu.rts22"), EventIdentity.of("LEI1", "XPAR", "E1"), Leg.MARKET);
    assertThatThrownBy(
            () ->
                new NewVersion(
                    subject,
                    "EXEC1",
                    Optional.empty(),
                    "NEWT",
                    Sha256.ofUtf8("h"),
                    null,
                    VersionOrigin.SOURCE,
                    new LeaseLedger.EndpointKey("SIMULATOR", "ARM", "LEI-EP1", "NCA-EP1"),
                    1))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("body");
  }

  @Test
  void aStoredVersionWithoutABodyIsRejectedByName() {
    assertThatThrownBy(
            () ->
                new StoredVersion(
                    new SubjectId(1),
                    1,
                    new DecisionId(1),
                    "NEWT",
                    Sha256.ofUtf8("h"),
                    null,
                    VersionOrigin.SOURCE,
                    Optional.empty()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("body");
    assertThat(
            new StoredVersion(
                    new SubjectId(1),
                    1,
                    new DecisionId(1),
                    "NEWT",
                    Sha256.ofUtf8("h"),
                    new byte[] {1},
                    VersionOrigin.SOURCE,
                    Optional.empty())
                .body())
        .containsExactly(1);
  }
}
