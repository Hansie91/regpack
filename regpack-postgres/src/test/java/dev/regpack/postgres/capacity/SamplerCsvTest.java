package dev.regpack.postgres.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The sampler's file format (T21, H-4): the header comes from what the server returns, never from a
 * list of column names in the code, and every field is escaped so that a value cannot start another
 * column or another row.
 */
class SamplerCsvTest {
  /** A result set over fixed rows: labels from the "server", {@code getObject} by position. */
  static ResultSet resultSet(List<String> labels, List<Object[]> rows) {
    ResultSetMetaData metaData =
        (ResultSetMetaData)
            Proxy.newProxyInstance(
                SamplerCsvTest.class.getClassLoader(),
                new Class<?>[] {ResultSetMetaData.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "getColumnCount" -> labels.size();
                      case "getColumnLabel" -> labels.get((Integer) args[0] - 1);
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    int[] cursor = {-1};
    return (ResultSet)
        Proxy.newProxyInstance(
            SamplerCsvTest.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getMetaData" -> metaData;
                  case "next" -> ++cursor[0] < rows.size();
                  case "getObject" -> rows.get(cursor[0])[(Integer) args[0] - 1];
                  case "close" -> null;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  @Test
  void headerComesFromTheResultSetAndRowsAreEscaped() throws Exception {
    // the labels are the server's, including one that no code of ours knows
    ResultSet wal =
        resultSet(
            List.of("wal_records", "wal_fpi", "wal_bytes", "brand_new_column", "stats_reset"),
            List.<Object[]>of(new Object[] {10L, 2L, 4_096L, 7L, java.time.Instant.EPOCH}));

    Map<String, String> sample = Sampler.flatten("wal", wal);

    // the timestamp is neither a number nor text, so it is not a column
    assertThat(sample.keySet())
        .containsExactly("wal.wal_records", "wal.wal_fpi", "wal.wal_bytes", "wal.brand_new_column");
    Sampler.Columns columns = Sampler.Columns.fixedBy(sample);
    assertThat(columns.header())
        .isEqualTo("wal.wal_records,wal.wal_fpi,wal.wal_bytes,wal.brand_new_column");
    assertThat(columns.row(sample)).isEqualTo("10,2,4096,7");

    // a later sample is projected onto the header: a new key is dropped, a missing one is empty
    Map<String, String> later = new LinkedHashMap<>();
    later.put("wal.wal_bytes", "5000");
    later.put("wal.surprise", "1");
    later.put("wal.wal_records", "12");
    assertThat(columns.row(later)).isEqualTo("12,,5000,");

    // escaping: a comma, a quote, a line feed and a carriage return stay inside one field
    assertThat(Sampler.csvLine(List.of("plain", "a,b", "say \"hi\"", "two\nlines", "cr\rx", "")))
        .isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",\"cr\rx\",");
  }

  @Test
  void aViewWithManyRowsIsSummedPerTheFirstTwoTextColumns() throws Exception {
    // the shape of pg_stat_io: backend_type, object, context, then counters (null where unused)
    ResultSet io =
        resultSet(
            List.of("backend_type", "object", "context", "reads", "read_time", "writes"),
            List.<Object[]>of(
                new Object[] {"client backend", "relation", "normal", 5L, 1.5, 2L},
                new Object[] {"client backend", "relation", "vacuum", 3L, 0.5, null},
                new Object[] {"client backend", "wal", "normal", 0L, 0.0, 9L},
                new Object[] {"background writer", "relation", "normal", 1L, 0.25, 4L},
                new Object[] {
                  "checkpointer", "relation", "normal", null, null, new BigDecimal("7")
                }));

    Map<String, String> sample = Sampler.flatten("io", io);

    assertThat(sample)
        .containsEntry("io.client_backend.relation.reads", "8")
        .containsEntry("io.client_backend.relation.read_time", "2.0")
        .containsEntry("io.client_backend.relation.writes", "2")
        .containsEntry("io.client_backend.wal.writes", "9")
        .containsEntry("io.background_writer.relation.reads", "1")
        .containsEntry("io.checkpointer.relation.writes", "7")
        .containsEntry("io.checkpointer.relation.reads", "0");
    // the text columns are keys, not columns of their own
    assertThat(sample.keySet())
        .noneMatch(key -> key.endsWith(".context") || key.endsWith(".object"));
  }

  @Test
  void procStatAndMeminfoBecomeColumns() {
    Map<String, String> stat =
        Sampler.parseProcStat(
            List.of(
                "cpu  100 5 50 800 20 1 2 3 0 0",
                "cpu0 25 1 12 200 5 0 1 1 0 0",
                "intr 12345",
                "ctxt 999"));
    assertThat(stat)
        .containsEntry("cpu.user", "100")
        .containsEntry("cpu.nice", "5")
        .containsEntry("cpu.system", "50")
        .containsEntry("cpu.idle", "800")
        .containsEntry("cpu.iowait", "20")
        .containsEntry("cpu.steal", "3")
        // each CPU has its own columns (the throughput gate is read per thread)
        .containsEntry("cpu0.user", "25")
        .containsEntry("cpu0.iowait", "5")
        .containsEntry("stat.ctxt", "999");

    Map<String, String> mem =
        Sampler.parseMeminfo(
            List.of(
                "MemTotal:       16384000 kB",
                "MemFree:         1000000 kB",
                "Dirty:                 8 kB",
                "HugePages_Total:       0",
                "garbage line"));
    assertThat(mem)
        .containsEntry("mem.MemTotal_kB", "16384000")
        .containsEntry("mem.MemFree_kB", "1000000")
        .containsEntry("mem.Dirty_kB", "8")
        .containsEntry("mem.HugePages_Total", "0")
        .hasSize(4);
  }

  @Test
  void aMissingProcFileGivesNoColumnsAndNoFailure() {
    assertThat(Sampler.parseProcStat(List.of())).isEmpty();
    assertThat(Sampler.parseMeminfo(List.of())).isEmpty();
  }
}
