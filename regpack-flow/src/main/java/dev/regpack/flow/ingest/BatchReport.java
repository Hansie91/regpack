package dev.regpack.flow.ingest;

import dev.regpack.model.BatchId;
import dev.regpack.ports.ledger.OutboxLedger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the ingest stage did with one source file (plan T7). Counts and coordinates only: no field
 * value is in a report (SF8).
 *
 * @param batchId the batch of the file (the digest of its bytes, slice 0); for a file refused
 *     before it was landed, the id of the refusal
 * @param file the name of the file in the inbox
 * @param verdict what became of the file, which fixes the folder it moves to. For a redelivery it
 *     is what the first delivery committed that decides (PROCESSED when it committed records), not
 *     the new reading of the bytes: a file whose first delivery committed nothing and whose bytes
 *     would now commit records (a defect that was fixed, a marker that was corrected) is REJECTED,
 *     because a batch is final for its bytes and a fix needs new bytes
 * @param redelivery true when the ledger already held this batch (a recorded no-op: the first
 *     delivery's items stand, the report is of what it committed)
 * @param declaredRecords the number of records the marker declares
 * @param ingested the records that were mapped and handed to the ledger (a record the ledger found
 *     stale or in conflict counts: the ledger's verdict is in its stored result and a DQ item); for
 *     a redelivery, the records of the stored result
 * @param quarantined the records of the file that were not handed to the ledger; for a batch held
 *     back as a whole, all its rows; for a redelivery rejected for new bytes, none
 * @param newVersions the entries the ledger took as a new version by this delivery, in file order;
 *     empty for a redelivery, because what still needs deciding is the state of the outbox item of
 *     the batch (D-W2), never a list in this report
 * @param outbox the outbox item of the batch, when the ledger made one
 */
public record BatchReport(
    BatchId batchId,
    String file,
    Verdict verdict,
    boolean redelivery,
    int declaredRecords,
    int ingested,
    int quarantined,
    List<FileIngest.Accepted> newVersions,
    Optional<OutboxLedger.OutboxId> outbox) {
  public BatchReport {
    Objects.requireNonNull(batchId, "batchId");
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(verdict, "verdict");
    if (declaredRecords < 0 || ingested < 0 || quarantined < 0) {
      throw new IllegalArgumentException("a count of a batch report must not be negative");
    }
    newVersions = List.copyOf(newVersions);
    Objects.requireNonNull(outbox, "outbox");
  }
}
