package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.ports.ledger.OutboxLedger.OutboxState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The outbox queries that need no server. */
class PgOutboxTest {
  @Test
  void thePendingQueryRepeatsTheStateCodeOfThePartialIndexPredicate() throws IOException {
    // a bind parameter would not let the planner prove the predicate of outbox_pending_idx, so the
    // query carries the code as a literal built from Codes; V001 holds the same number
    String predicate = "state = " + Codes.of(OutboxState.PENDING);
    assertThat(PgOutbox.PENDING_SQL).contains(predicate).doesNotContain("state = ?");
    Path migration =
        Path.of(System.getProperty("regpack.classes.dir"), "db/migration/V001__core.sql");
    String v001 = Files.readString(migration, StandardCharsets.UTF_8);
    assertThat(v001)
        .contains("CREATE INDEX outbox_pending_idx ON outbox (outbox_id) WHERE " + predicate);
  }
}
