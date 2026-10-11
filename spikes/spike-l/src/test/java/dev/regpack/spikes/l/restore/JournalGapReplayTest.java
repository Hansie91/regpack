package dev.regpack.spikes.l.restore;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.restore.JournalGapReplay.Obj;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Replay is checked against a scripted connection that behaves like the two statements of {@link
 * JournalGapReplay}: an insert with {@code ON CONFLICT DO NOTHING} on (scope, epoch, seq) and a
 * counter upsert that keeps the larger sequence number. The SQL itself is proven on the runner
 * (RestoreIT).
 */
class JournalGapReplayTest {
  /** The store that the scripted statements write to. */
  private static final class Store {
    final Set<String> keys = new LinkedHashSet<>();
    final List<String> insertionOrder = new ArrayList<>();
    final Map<String, Long> counters = new HashMap<>();
    final List<String> statements = new ArrayList<>();

    Connection connection() {
      return (Connection)
          Proxy.newProxyInstance(
              Store.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "createArrayOf" -> {
                    Object[] elements = (Object[]) args[1];
                    return (Array)
                        Proxy.newProxyInstance(
                            Store.class.getClassLoader(),
                            new Class<?>[] {Array.class},
                            (p, m, a) -> m.getName().equals("getArray") ? elements : null);
                  }
                  case "prepareStatement" -> {
                    return statement((String) args[0]);
                  }
                  default -> {
                    return defaultValue(method.getReturnType());
                  }
                }
              });
    }

    private PreparedStatement statement(String sql) {
      statements.add(sql);
      Object[][] arrays = new Object[5][];
      return (PreparedStatement)
          Proxy.newProxyInstance(
              Store.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "setArray" -> {
                    arrays[(Integer) args[0]] = (Object[]) ((Array) args[1]).getArray();
                    return null;
                  }
                  case "executeUpdate" -> {
                    return run(sql, arrays);
                  }
                  default -> {
                    return defaultValue(method.getReturnType());
                  }
                }
              });
    }

    private int run(String sql, Object[][] arrays) {
      int rows = arrays[1].length;
      if (sql.contains("journal_applied")) {
        int inserted = 0;
        for (int i = 0; i < rows; i++) {
          String key = arrays[1][i] + "/" + arrays[2][i] + "/" + arrays[3][i];
          if (keys.add(key)) {
            insertionOrder.add(key);
            inserted++;
          }
        }
        return inserted;
      }
      for (int i = 0; i < rows; i++) {
        counters.merge(arrays[1][i] + "/" + arrays[2][i], (Long) arrays[3][i], Math::max);
      }
      return counters.size();
    }
  }

  private static Object defaultValue(Class<?> type) {
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }

  private static String key(Obj o) {
    return o.scope() + "/" + o.epoch() + "/" + o.seq();
  }

  @Test
  void generationIsDeterministicUniqueAndNotInOrder() {
    List<Obj> a = JournalGapReplay.generate(7L, 500);
    List<Obj> b = JournalGapReplay.generate(7L, 500);
    assertThat(a).isEqualTo(b);
    assertThat(JournalGapReplay.generate(8L, 500)).isNotEqualTo(a);
    assertThat(a).hasSize(500);
    assertThat(a.stream().map(JournalGapReplayTest::key).distinct().count()).isEqualTo(500);
    assertThat(a.stream().map(Obj::scope).distinct().count()).isGreaterThan(1);
    List<Obj> sorted = new ArrayList<>(a);
    sorted.sort(JournalGapReplay.ORDER);
    assertThat(a).as("generated in arrival order, not in key order").isNotEqualTo(sorted);
  }

  @Test
  void replayIsIdempotentByKey() throws Exception {
    Store store = new Store();
    List<Obj> objects = JournalGapReplay.generate(7L, 500);
    Connection c = store.connection();

    assertThat(JournalGapReplay.apply(c, "sp_restore", objects)).isEqualTo(500);
    assertThat(store.keys).hasSize(500);
    // the second replay of the same gap changes nothing
    assertThat(JournalGapReplay.apply(c, "sp_restore", objects)).isZero();
    assertThat(store.keys).hasSize(500);
    // the counter of each (scope, epoch) holds the largest sequence number replayed
    Map<String, Long> expected = new HashMap<>();
    for (Obj o : objects) {
      expected.merge(o.scope() + "/" + o.epoch(), o.seq(), Math::max);
    }
    assertThat(store.counters).isEqualTo(expected);
    // a replay with duplicates inside the list counts each key once
    List<Obj> twice = new ArrayList<>(objects);
    twice.addAll(objects.subList(0, 100));
    assertThat(JournalGapReplay.apply(store.connection(), "sp_restore", twice)).isZero();
  }

  @Test
  void objectsAreAppliedInSeqOrder() throws Exception {
    Store store = new Store();
    List<Obj> shuffled = new ArrayList<>(JournalGapReplay.generate(11L, 2_500));
    Collections.shuffle(shuffled, new java.util.Random(5));
    JournalGapReplay.apply(store.connection(), "sp_restore", shuffled);

    List<Obj> sorted = new ArrayList<>(shuffled);
    sorted.sort(
        Comparator.comparingInt(Obj::scope)
            .thenComparingInt(Obj::epoch)
            .thenComparingLong(Obj::seq));
    assertThat(store.insertionOrder)
        .isEqualTo(sorted.stream().map(JournalGapReplayTest::key).toList());
    // more than one batch was needed, and a batch is a statement pair
    assertThat(store.statements.size()).isGreaterThan(2);
  }

  @Test
  void theSqlIsIdempotentAndKeepsTheLargerCounter() {
    assertThat(JournalGapReplay.INSERT_SQL)
        .contains("ON CONFLICT (scope, epoch, seq) DO NOTHING")
        .contains("unnest(?::int[], ?::int[], ?::bigint[], ?::smallint[])")
        // an INSERT ... SELECT without WHERE cannot be followed by ON CONFLICT
        .contains("WHERE true ORDER BY u.scope, u.epoch, u.seq");
    assertThat(JournalGapReplay.COUNTER_SQL)
        .contains("ON CONFLICT (endpoint_id, restore_epoch) DO UPDATE")
        .contains("GREATEST(");
    assertThat(JournalGapReplay.createTableSql("sp_restore"))
        .contains("CREATE TABLE IF NOT EXISTS \"sp_restore\".journal_applied")
        .contains("PRIMARY KEY (scope, epoch, seq)");
  }
}
