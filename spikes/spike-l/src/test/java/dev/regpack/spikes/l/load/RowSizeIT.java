package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.spikes.l.load.LedgerLoadModel.Candidate;
import dev.regpack.spikes.l.load.LedgerLoadModel.Params;
import dev.regpack.spikes.l.load.MicroBatchWriter.Method;
import dev.regpack.spikes.l.pg.PgFixture;
import dev.regpack.spikes.l.schema.SchemaInstaller;
import dev.regpack.spikes.l.schema.SchemaInstaller.Variant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs only in the smoke dispatch: the size of a decision row with its indexes, per encoding.
 *
 * <p>The two encodings are alternatives: E1 keeps the packed path in {@code body}, E3 keeps the
 * verbose path in {@code path jsonb} and leaves {@code body} NULL, so the total of a variant is its
 * own and the two totals are compared as they are. The test asserts what is not true by
 * construction: that E3 really stores no body and that its path is several times the E1 body.
 */
@EnabledIfSystemProperty(named = "spike.pg.url", matches = ".+")
class RowSizeIT {
  private static final int BATCH = 5_000;
  private static final int BATCHES = 40; // 200,000 candidates

  /**
   * @param payloadBytes stored bytes of the encoding's own column over all rows: body for E1, path
   *     for E3 (as {@code pg_column_size}, so compression is counted)
   * @param rawPayloadBytes the same column before storage: the bytes of the body, the characters of
   *     the path as text
   * @param bodies rows whose body is not NULL
   */
  private record Size(
      String variant,
      long rows,
      long heapBytes,
      long indexBytes,
      long payloadBytes,
      long rawPayloadBytes,
      long bodies) {
    long total() {
      return heapBytes + indexBytes;
    }

    double perRow() {
      return total() / (double) rows;
    }
  }

  private static Size measure(PgFixture pg, Variant variant, String schema) throws Exception {
    LocalDate day = new TradeRecordGen(1).next().tradeDate();
    try (Connection c = pg.primary()) {
      SchemaInstaller.drop(c, schema);
      SchemaInstaller.install(c, schema, variant, day, 1);
    }
    Params params = new Params(BATCH, 0.5, 1.5, 0.02, 99);
    TradeRecordGen gen = new TradeRecordGen(5);
    SplitMix64 rng = new SplitMix64(6);
    List<String> existing = new ArrayList<>();
    try (Connection c = pg.primary()) {
      MicroBatchWriter writer = new MicroBatchWriter(c, schema, Method.COPY, variant);
      for (int b = 1; b <= BATCHES; b++) {
        List<Candidate> batch = LedgerLoadModel.nextBatch(params, gen, rng, existing);
        writer.write(b, batch);
        for (Candidate cand : batch) {
          if (cand.report() && !cand.superseding()) {
            existing.add(cand.eventIdentity());
          }
        }
      }
    }
    String qualified = "\"" + schema + "\".decision";
    try (Connection c = pg.primary();
        Statement st = c.createStatement()) {
      c.setAutoCommit(true);
      List<String> leaves = new ArrayList<>();
      try (ResultSet rs =
          st.executeQuery(
              "SELECT relid::regclass::text FROM pg_partition_tree('"
                  + qualified
                  + "'::regclass) WHERE isleaf")) {
        while (rs.next()) {
          leaves.add(rs.getString(1));
        }
      }
      for (String leaf : leaves) {
        st.execute("VACUUM (ANALYZE) " + leaf);
      }
      long rows;
      long bodies;
      long payload;
      long raw;
      String payloadColumn = variant == Variant.E1_COMPACT ? "body" : "path";
      String rawLength =
          variant == Variant.E1_COMPACT ? "octet_length(body)" : "length(path::text)";
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*), count(body), COALESCE(sum(pg_column_size("
                  + payloadColumn
                  + ")), 0), COALESCE(sum("
                  + rawLength
                  + "), 0) FROM "
                  + qualified)) {
        rs.next();
        rows = rs.getLong(1);
        bodies = rs.getLong(2);
        payload = rs.getLong(3);
        raw = rs.getLong(4);
      }
      long heap;
      long index;
      try (ResultSet rs =
          st.executeQuery(
              "SELECT COALESCE(sum(pg_table_size(relid)), 0),"
                  + " COALESCE(sum(pg_indexes_size(relid)), 0)"
                  + " FROM pg_partition_tree('"
                  + qualified
                  + "'::regclass) WHERE isleaf")) {
        rs.next();
        heap = rs.getLong(1);
        index = rs.getLong(2);
      }
      return new Size(variant.name(), rows, heap, index, payload, raw, bodies);
    }
  }

  @Test
  void bytesPerDecisionRowAreOrderedByVariant() throws Exception {
    try (PgFixture pg = PgFixture.fromSystemProperty().orElseThrow()) {
      Size e1 = measure(pg, Variant.E1_COMPACT, "sp_rowsize_e1");
      Size e3 = measure(pg, Variant.E3_VERBOSE, "sp_rowsize_e3");
      assertThat(e1.rows()).isEqualTo(BATCH * (long) BATCHES);
      assertThat(e3.rows()).isEqualTo(BATCH * (long) BATCHES);
      assertThat(e1.heapBytes()).isPositive();
      assertThat(e1.indexBytes()).isPositive();
      // the encodings are alternatives: E1 has a body in every row, E3 has none
      assertThat(e1.bodies()).isEqualTo(e1.rows());
      assertThat(e3.bodies()).isZero();
      assertThat(e1.payloadBytes()).isPositive();
      // not true by construction: the verbose path is several times the packed body (MicroBatch-
      // WriterTest checks the same ratio of the generator offline); compression may narrow the
      // stored ratio, so the raw one is asserted and both are recorded
      assertThat(e3.rawPayloadBytes()).isGreaterThanOrEqualTo(5 * e1.rawPayloadBytes());
      assertThat(e3.payloadBytes()).isGreaterThan(e1.payloadBytes());
      assertThat(e1.total()).isLessThanOrEqualTo(e3.total());

      Path out = Path.of("target", "row-size.tsv");
      Files.createDirectories(out.getParent());
      StringBuilder tsv =
          new StringBuilder(
              "variant\trows\theap_bytes\tindex_bytes\ttotal_bytes\tpayload_bytes"
                  + "\traw_payload_bytes\n");
      for (Size s : List.of(e1, e3)) {
        tsv.append(s.variant())
            .append('\t')
            .append(s.rows())
            .append('\t')
            .append(s.heapBytes())
            .append('\t')
            .append(s.indexBytes())
            .append('\t')
            .append(s.total())
            .append('\t')
            .append(s.payloadBytes())
            .append('\t')
            .append(s.rawPayloadBytes())
            .append('\n');
      }
      Files.writeString(out, tsv.toString());
    }
  }
}
