package dev.regpack.spikes.l.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** The offline part of criterion L-12: the ten check names that the report counts. */
class ConstraintChecksNamesTest {
  @Test
  void theTenChecksAreNamedAfterTheContractTests() {
    assertThat(ConstraintChecks.NAMES)
        .containsExactly(
            "inFlightGuardAllowsOnlyOneDeliveryPerBusinessKey",
            "markCommitIssuedIsFencedCompareAndSet",
            "takeoverMovesSendingDeliveriesToInDoubtAndFencesTheOldHolder",
            "staleExpectedHeadSeqIsRejectedByHeadCompareAndSet",
            "crossPartitionDuplicateDecisionSeqIsRejectedByHead",
            "redeliveryOfADecisionIsNoopThroughOnConflict",
            "restoreEpochTokenPairIsComparedAsAWhole",
            "crossPartitionSameVersionDifferentHashNeedsAnUnpartitionedRegistry",
            "reportSubjectIsRegimeScoped",
            "trnUniquenessIsPerExecutingLei");
    assertThat(new HashSet<>(ConstraintChecks.NAMES)).hasSize(10);
  }

  @Test
  void everyCheckGetsItsOwnShortSchemaName() {
    for (int i = 0; i < ConstraintChecks.NAMES.size(); i++) {
      String schema = ConstraintChecks.schemaName(i);
      assertThat(schema).matches("sp_chk_c\\d\\d");
      assertThat(SchemaInstaller.quote(schema)).isNotEmpty();
    }
    assertThat(ConstraintChecks.schemaName(0)).isNotEqualTo(ConstraintChecks.schemaName(1));
  }
}
