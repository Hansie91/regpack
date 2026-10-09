package dev.regpack.testkit.contract;

import dev.regpack.model.BatchId;
import dev.regpack.model.Checkpoint;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.IngestKey;
import dev.regpack.model.Sha256;
import dev.regpack.ports.ledger.IngestLedger;
import java.util.List;
import java.util.Optional;

/** Helpers shared by the ledger contract suites. */
public final class LedgerFixtures {
  private LedgerFixtures() {}

  /**
   * An entry whose key is (sourceId, recordId, version), whose event identity is {@code
   * LEI1|XPAR|recordId} and whose hash is the SHA-256 of {@code content}.
   */
  public static IngestLedger.Entry entry(
      String sourceId, String recordId, long version, String content) {
    return new IngestLedger.Entry(
        new IngestKey(sourceId, recordId, version),
        EventIdentity.of("LEI1", "XPAR", recordId),
        Sha256.ofUtf8(content));
  }

  /** A commit whose batch id is {@code BatchId.of(Sha256.ofUtf8(batchTag), 0)}. */
  public static IngestLedger.Commit commit(
      String batchTag, String sourceId, Checkpoint cp, IngestLedger.Entry... entries) {
    return new IngestLedger.Commit(batchId(batchTag), sourceId, List.of(entries), Optional.of(cp));
  }

  /** The batch id that {@link #commit} derives from {@code batchTag}. */
  public static BatchId batchId(String batchTag) {
    return BatchId.of(Sha256.ofUtf8(batchTag), 0);
  }

  /** A checkpoint on stream {@code trades} whose state is the single byte {@code n}. */
  public static Checkpoint checkpoint(int n) {
    return new Checkpoint("trades", new byte[] {(byte) n});
  }
}
