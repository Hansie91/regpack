package dev.regpack.evidence.sealed;

import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.testkit.clock.ManualClock;
import dev.regpack.testkit.contract.EvidenceStoreContract;
import dev.regpack.testkit.contract.JournalContract;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Binds the shared suites {@link EvidenceStoreContract} and {@link JournalContract} to {@link
 * SealedEvidenceStore} over two directories of a {@code @TempDir} (T7). {@link #simulateOutage}
 * switches a {@link FaultableFileOps} that makes every file operation fail; {@link #reopen} opens
 * the same two directories again with the real file operations, as after a restart.
 */
abstract class AbstractSealedContractTest implements EvidenceStoreContract, JournalContract {
  @TempDir Path root;

  private ManualClock clock;
  private FaultableFileOps ops;
  private SealedEvidenceStore.Config config;
  private SealedEvidenceStore store;

  @BeforeEach
  void openStore() throws IOException {
    clock = new ManualClock(Instant.parse("2026-03-01T00:00:00Z"));
    Path base = root.toRealPath();
    config =
        new SealedEvidenceStore.Config(
            base.resolve("vol-a"), base.resolve("vol-b"), "vol-a", "vol-b", clock);
    ops = new FaultableFileOps(FileOps.real());
    store = SealedEvidenceStore.open(config, ops);
  }

  @Override
  public EvidenceStore store() {
    return store;
  }

  @Override
  public ManualClock clock() {
    return clock;
  }

  @Override
  public void simulateOutage(boolean down) {
    ops.setDown(down);
  }

  @Override
  public EvidenceStore reopen() {
    return SealedEvidenceStore.open(config);
  }
}
