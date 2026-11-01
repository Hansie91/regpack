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
 * @param verdict what became of the file, which fixes the folder it moves to
 * @param redelivery true when the ledger already held this batch (a recorded no-op)
 * @param declaredRecords the number of records the marker declares
 * @param ingested the records that were mapped and handed to the ledger (a record the ledger found
 *     stale or in conflict counts: the ledger's verdict is in its stored result and a DQ item)
 * @param quarantined the records of the file that were not handed to the ledger; for a batch held
 *     back as a whole, all its rows
 * @param newVersions the entries the ledger took as a new version, in file order
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
