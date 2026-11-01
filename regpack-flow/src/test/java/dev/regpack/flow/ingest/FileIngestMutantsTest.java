package dev.regpack.flow.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.flow.ingest.FileIngest.Flaw;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The teeth of the ingest stage (plan Convention 3, SF6, SF10): each one-line flaw of the stage,
 * switched on through the package-private seam, must make the named scenario of {@link
 * FileIngestTest} fail, and the same scenario with {@link Flaw#NONE} passes, so the flaw and
 * nothing else is what the scenario catches.
 *
 * <p>The plan's mutant "move before acknowledge" has no separate meaning here: the connector moves
 * a file inside {@code acknowledge} and nowhere else. It is read as the order flaw that is left,
 * the verdict that fixes the folder of the move being set after the acknowledge.
 */
class FileIngestMutantsTest {
  @TempDir Path root;

  @Test
  void theUnflawedStagePassesEveryNamedScenario() {
    FileIngestTest.goodFileOrder(new IngestFixture(root.resolve("a"), Flaw.NONE));
    FileIngestTest.ledgerExceptionAtCommit(new IngestFixture(root.resolve("b"), Flaw.NONE));
    FileIngestTest.brokenHeader(new IngestFixture(root.resolve("c"), Flaw.NONE));
    FileIngestTest.duplicateKey(new IngestFixture(root.resolve("d"), Flaw.NONE));
    FileIngestTest.mapperDefect(root.resolve("e"), Flaw.NONE);
    FileIngestTest.anotherCountAfterProcessed(new IngestFixture(root.resolve("f"), Flaw.NONE));
    FileIngestTest.anotherSumAfterProcessed(new IngestFixture(root.resolve("g"), Flaw.NONE));
    FileIngestTest.correctedMarkerAfterQuarantine(new IngestFixture(root.resolve("h"), Flaw.NONE));
    FileIngestTest.redeliveryAfterAFixedDefect(root.resolve("i"), Flaw.NONE);
    FileIngestTest.failedStaleItem(new IngestFixture(root.resolve("j"), Flaw.NONE));
    FileIngestTest.failedVersionConflictItem(new IngestFixture(root.resolve("k"), Flaw.NONE));
  }

  @Test
  void acknowledgingBeforeTheCommitFailsTheOrderAndTheLedgerFailureScenarios() {
    assertThatThrownBy(
            () ->
                FileIngestTest.goodFileOrder(
                    new IngestFixture(root.resolve("a"), Flaw.ACKNOWLEDGE_BEFORE_COMMIT)))
        .isInstanceOf(AssertionError.class);
    // the file moved although the ledger never took the batch: the data would be gone
    assertThatThrownBy(
            () ->
                FileIngestTest.ledgerExceptionAtCommit(
                    new IngestFixture(root.resolve("b"), Flaw.ACKNOWLEDGE_BEFORE_COMMIT)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void settingTheVerdictAfterTheAcknowledgeFailsTheBrokenHeaderScenario() {
    assertThatThrownBy(
            () ->
                FileIngestTest.brokenHeader(
                    new IngestFixture(root, Flaw.VERDICT_AFTER_ACKNOWLEDGE)))
        // the connector refuses a verdict for a file that has already moved
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unmoved");
  }

  @Test
  void skippingTheAdmittedPutFailsTheOrderScenario() {
    assertThatThrownBy(
            () -> FileIngestTest.goodFileOrder(new IngestFixture(root, Flaw.SKIP_ADMITTED_PUT)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void removingTheDuplicateCheckFailsTheDuplicateKeyScenario() {
    // the ledger refuses a commit with a duplicate key: the call throws instead of quarantining
    assertThatThrownBy(
            () -> FileIngestTest.duplicateKey(new IngestFixture(root, Flaw.NO_DUPLICATE_CHECK)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate ingest key");
  }

  @Test
  void removingTheDefectHandlerFailsTheMapperDefectScenario() {
    assertThatThrownBy(() -> FileIngestTest.mapperDefect(root, Flaw.NO_DEFECT_HANDLER))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void skippingTheRedeliveryCheckFailsTheSameBytesUnderAnotherMarkerScenarios() {
    // the items of the second marker are put under the keys of the first marker's items: the call
    // throws, the file never moves and the file behind it is never read
    assertThatThrownBy(
            () ->
                FileIngestTest.anotherCountAfterProcessed(
                    new IngestFixture(root.resolve("a"), Flaw.SKIP_REDELIVERY_CHECK)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DQ item key");
    assertThatThrownBy(
            () ->
                FileIngestTest.anotherSumAfterProcessed(
                    new IngestFixture(root.resolve("b"), Flaw.SKIP_REDELIVERY_CHECK)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DQ item key");
    assertThatThrownBy(
            () ->
                FileIngestTest.correctedMarkerAfterQuarantine(
                    new IngestFixture(root.resolve("c"), Flaw.SKIP_REDELIVERY_CHECK)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DQ item key");
  }

  @Test
  void takingTheVerdictOfARedeliveryFromTheNewParseFailsTheFixedDefectAndTheCorrectedMarker() {
    // the defect is fixed, the same bytes come again: the new parse says PROCESSED with two
    // records that the ledger never had, and the file would move to processed/
    assertThatThrownBy(
            () ->
                FileIngestTest.redeliveryAfterAFixedDefect(
                    root.resolve("a"), Flaw.VERDICT_FROM_NEW_PARSE))
        .isInstanceOf(AssertionError.class);
    // a corrected marker after a held batch has the same shape
    assertThatThrownBy(
            () ->
                FileIngestTest.correctedMarkerAfterQuarantine(
                    new IngestFixture(root.resolve("b"), Flaw.VERDICT_FROM_NEW_PARSE)))
        .isInstanceOf(AssertionError.class);
    // and a marker with a wrong total after a processed delivery would hold the file back
    // although its records are in the ledger
    assertThatThrownBy(
            () ->
                FileIngestTest.anotherCountAfterProcessed(
                    new IngestFixture(root.resolve("c"), Flaw.VERDICT_FROM_NEW_PARSE)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void acknowledgingBeforeTheDispositionItemsFailsTheFailedItemScenarios() {
    // the file moves right after the commit, then the item of the stale version cannot be written:
    // the file is gone from the inbox and nothing ever turns the stored result into an item
    assertThatThrownBy(
            () ->
                FileIngestTest.failedStaleItem(
                    new IngestFixture(root.resolve("a"), Flaw.ACKNOWLEDGE_BEFORE_DISPOSITIONS)))
        .isInstanceOf(AssertionError.class);
    // and a conflict of versions is the same
    assertThatThrownBy(
            () ->
                FileIngestTest.failedVersionConflictItem(
                    new IngestFixture(root.resolve("b"), Flaw.ACKNOWLEDGE_BEFORE_DISPOSITIONS)))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void everyFlawIsNamedHere() {
    assertThat(Flaw.values())
        .extracting(Flaw::name)
        .containsExactly(
            "NONE",
            "ACKNOWLEDGE_BEFORE_COMMIT",
            "VERDICT_AFTER_ACKNOWLEDGE",
            "SKIP_ADMITTED_PUT",
            "NO_DUPLICATE_CHECK",
            "NO_DEFECT_HANDLER",
            "SKIP_REDELIVERY_CHECK",
            "VERDICT_FROM_NEW_PARSE",
            "ACKNOWLEDGE_BEFORE_DISPOSITIONS");
  }
}
