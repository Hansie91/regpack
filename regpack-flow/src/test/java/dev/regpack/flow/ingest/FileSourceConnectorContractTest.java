package dev.regpack.flow.ingest;

import dev.regpack.ports.connector.SourceConnector;
import dev.regpack.testkit.contract.SourceConnectorContract;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/** The shared source contract (SF5) for the file source: one one-row CSV and its marker a batch. */
class FileSourceConnectorContractTest implements SourceConnectorContract {
  @TempDir Path root;

  private FileSourceConnector current;

  @Override
  public SourceConnector connector(int batchCount) {
    InboxFixture fixture = new InboxFixture(root);
    for (int i = 1; i <= batchCount; i++) {
      String id = String.format(java.util.Locale.ROOT, "%03d", i);
      fixture.drop("trades-" + id + ".csv", InboxFixture.oneRow("T-" + id, "EX-" + id));
    }
    current = fixture.connector();
    return current;
  }

  @Override
  public void armFailureOnNextRead() {
    current.failNextRead();
  }

  @Override
  public String mediaType() {
    return "text/csv";
  }

  @Override
  public int expectedRecordCount() {
    return 1;
  }
}
