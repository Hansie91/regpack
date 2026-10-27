package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.model.Regime;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.RetentionLedger;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.testkit.clock.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Contract of the {@code retention} rows of a {@link LedgerStore} (spec 11.6; P3c1 D-K4, A8, D2, D3
 * and D6 of the V003 document): one row per (artifact, regime, anchor event), a date that can only
 * grow and never goes back to unknown, one legal hold per artifact that a new row of the artifact
 * also takes, a list of due rows that leaves out held rows and epoch markers, and paging in byte
 * order. The port is reached by a cast of {@link #ledger()} until {@code LedgerStore} extends it.
 *
 * <p>Every assertion is adapter-neutral: the literal orders below are byte orders of the key and
 * regime texts, never the order in which an adapter happens to hold them.
 */
public interface RetentionLedgerContract {
  /** A fresh, empty ledger for each test. */
  LedgerStore ledger();

  /** The clock the ledger reads. */
  ManualClock clock();

  private RetentionLedger retention() {
    return (RetentionLedger) ledger();
  }

  private static Instant at(String iso) {
    return Instant.parse(iso);
  }

  /** A date after every date these tests use, the last microsecond of year 9999. */
  private static Instant farFuture() {
    return at("9999-12-31T23:59:59.999999Z");
  }

  private static RetentionClaim claim(
      String artifact, String regime, AnchorEvent anchor, Optional<Instant> until, String cls) {
    return new RetentionClaim(new EvidenceKey(artifact), new Regime(regime), anchor, until, cls);
  }

  /** An OBJECT_WRITTEN claim of class {@code sealed}. */
  private static RetentionClaim claim(String artifact, String regime, Instant until) {
    return claim(artifact, regime, AnchorEvent.OBJECT_WRITTEN, Optional.of(until), "sealed");
  }

  private static RetentionRow row(RetentionClaim claim, boolean held, Instant recordedAt) {
    return new RetentionRow(claim, held, recordedAt);
  }

  private List<RetentionRow> every() {
    return retention().retentionRows("", Optional.empty(), 1_000);
  }

  private List<RetentionRow> due() {
    return retention().expiredRetention(farFuture(), Optional.empty(), 1_000);
  }

  private static List<String> triples(List<RetentionRow> rows) {
    List<String> out = new ArrayList<>();
    for (RetentionRow each : rows) {
      out.add(
          each.claim().artifact().path()
              + "|"
              + each.claim().regime().id()
              + "|"
              + each.claim().anchor());
    }
    return out;
  }

  private static List<String> artifacts(List<RetentionRow> rows) {
    List<String> out = new ArrayList<>();
    for (RetentionRow each : rows) {
      String path = each.claim().artifact().path();
      if (out.isEmpty() || !out.get(out.size() - 1).equals(path)) {
        out.add(path);
      }
    }
    return out;
  }

  /**
   * The pages of {@code retentionRows} with {@code limit}, walked with the artifact of the last
   * row.
   */
  private List<List<RetentionRow>> walkRetentionRows(String prefix, int limit) {
    List<List<RetentionRow>> pages = new ArrayList<>();
    Optional<EvidenceKey> after = Optional.empty();
    for (int guard = 0; guard < 2_000; guard++) {
      List<RetentionRow> page = retention().retentionRows(prefix, after, limit);
      if (page.isEmpty()) {
        return pages;
      }
      pages.add(page);
      after = Optional.of(page.get(page.size() - 1).claim().artifact());
    }
    throw new AssertionError("the walk of retentionRows does not end");
  }

  /** The pages of {@code expiredRetention} with {@code limit}, walked with the last row. */
  private List<List<RetentionRow>> walkExpired(Instant asOf, int limit) {
    List<List<RetentionRow>> pages = new ArrayList<>();
    Optional<RetentionRow> after = Optional.empty();
    for (int guard = 0; guard < 2_000; guard++) {
      List<RetentionRow> page = retention().expiredRetention(asOf, after, limit);
      if (page.isEmpty()) {
        return pages;
      }
      pages.add(page);
      after = Optional.of(page.get(page.size() - 1));
    }
    throw new AssertionError("the walk of expiredRetention does not end");
  }

  private static List<RetentionRow> flatten(List<List<RetentionRow>> pages) {
    List<RetentionRow> out = new ArrayList<>();
    pages.forEach(out::addAll);
    return out;
  }

  @Test
  default void aRecordedClaimIsReadBackWithTheLedgerClock() {
    clock().advance(Duration.ofSeconds(90));
    Instant firstAt = clock().now();
    RetentionClaim sealed =
        claim("sealed/ab/" + "c".repeat(64), "mifir.eu.rts22", at("2031-06-01T12:30:45.123456Z"));
    retention().recordRetention(List.of(sealed));
    clock().advance(Duration.ofHours(1));

    assertThat(every()).containsExactly(row(sealed, false, firstAt));

    // an open anchor is stored as it is, with the clock of its own call
    Instant secondAt = clock().now();
    RetentionClaim open =
        claim("landing/b1", "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "landing");
    retention().recordRetention(List.of(open));
    assertThat(every()).containsExactly(row(open, false, secondAt), row(sealed, false, firstAt));

    // the same claim again does not move the time of the row
    clock().advance(Duration.ofDays(1));
    retention().recordRetention(List.of(sealed, open));
    assertThat(every()).containsExactly(row(open, false, secondAt), row(sealed, false, firstAt));

    // the first and the last storable date are kept as they are
    RetentionClaim first = claim("sealed/first", "mifir.eu.rts22", at("0000-01-01T00:00:00Z"));
    RetentionClaim last = claim("sealed/last", "mifir.eu.rts22", farFuture());
    retention().recordRetention(List.of(last, first));
    assertThat(retention().retentionRows("sealed/first", Optional.empty(), 5))
        .extracting(r -> r.claim().retainUntil())
        .containsExactly(Optional.of(at("0000-01-01T00:00:00Z")));
    assertThat(retention().retentionRows("sealed/last", Optional.empty(), 5))
        .extracting(r -> r.claim().retainUntil())
        .containsExactly(Optional.of(farFuture()));
  }

  @Test
  default void aLaterRetainUntilReplacesAndAnEarlierOneIsIgnored() {
    Instant firstAt = clock().now();
    Instant t1 = at("2031-01-01T00:00:00Z");
    Instant t2 = at("2033-01-01T00:00:00Z");
    String key = "sealed/ab/merge";
    retention().recordRetention(List.of(claim(key, "mifir.eu.rts22", t1)));

    // later: the date grows; the class and the time of the row stay
    clock().advance(Duration.ofDays(3));
    retention()
        .recordRetention(
            List.of(
                claim(
                    key,
                    "mifir.eu.rts22",
                    AnchorEvent.OBJECT_WRITTEN,
                    Optional.of(t2),
                    "other-class")));
    RetentionClaim expected = claim(key, "mifir.eu.rts22", t2);
    assertThat(every()).containsExactly(row(expected, false, firstAt));

    // earlier, equal and one microsecond earlier: nothing changes
    for (Instant notLater : List.of(t1, t2, t2.minus(Duration.ofNanos(1_000)))) {
      retention().recordRetention(List.of(claim(key, "mifir.eu.rts22", notLater)));
      assertThat(every()).as("claim %s", notLater).containsExactly(row(expected, false, firstAt));
    }

    // one microsecond later is later
    Instant t3 = t2.plus(Duration.ofNanos(1_000));
    retention().recordRetention(List.of(claim(key, "mifir.eu.rts22", t3)));
    assertThat(every()).containsExactly(row(claim(key, "mifir.eu.rts22", t3), false, firstAt));
  }

  @Test
  default void anOpenAnchorTakesAValueAndNeverBecomesOpenAgain() {
    Instant firstAt = clock().now();
    String key = "landing/open1";
    RetentionClaim open =
        claim(key, "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "landing");
    retention().recordRetention(List.of(open));
    assertThat(every()).containsExactly(row(open, false, firstAt));

    // an empty claim on an empty row, with another class: still empty, the class stays
    clock().advance(Duration.ofHours(2));
    retention()
        .recordRetention(
            List.of(claim(key, "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "other")));
    assertThat(every()).containsExactly(row(open, false, firstAt));

    // a value replaces the empty date; class and time stay
    Instant date = at("2032-03-04T05:06:07Z");
    retention()
        .recordRetention(
            List.of(claim(key, "emir.eu", AnchorEvent.TERMINATION, Optional.of(date), "other")));
    RetentionClaim valued =
        claim(key, "emir.eu", AnchorEvent.TERMINATION, Optional.of(date), "landing");
    assertThat(every()).containsExactly(row(valued, false, firstAt));

    // an empty claim never replaces a value
    retention().recordRetention(List.of(open));
    assertThat(every()).containsExactly(row(valued, false, firstAt));

    // the OBJECT_WRITTEN row of the same artifact and regime is a row of its own
    retention()
        .recordRetention(
            List.of(
                claim(
                    key,
                    "emir.eu",
                    AnchorEvent.OBJECT_WRITTEN,
                    Optional.of(at("2030-01-01T00:00:00Z")),
                    "landing")));
    assertThat(every()).hasSize(2);
    assertThat(every().get(1)).isEqualTo(row(valued, false, firstAt));
  }

  @Test
  default void twoRegimesGiveTwoRowsAndOneRegimeTwiceGivesOne() {
    String key = "sealed/ab/regimes";
    Instant t = at("2031-01-01T00:00:00Z");
    retention()
        .recordRetention(
            List.of(claim(key, "mifir.eu.rts22", t), claim(key, "emir.eu", t.plusSeconds(60))));
    retention().recordRetention(List.of(claim(key, "mifir.eu.rts22", t)));
    retention().recordRetention(List.of(claim(key, "emir.eu", t)));

    // two rows, in regime order; each keeps its own date
    assertThat(triples(every()))
        .containsExactly(key + "|emir.eu|OBJECT_WRITTEN", key + "|mifir.eu.rts22|OBJECT_WRITTEN");
    assertThat(every())
        .extracting(r -> r.claim().retainUntil().orElseThrow())
        .containsExactly(t.plusSeconds(60), t);

    // the anchor event is the third part of the key: a third row
    retention()
        .recordRetention(
            List.of(claim(key, "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "sealed")));
    assertThat(triples(every()))
        .containsExactly(
            key + "|emir.eu|OBJECT_WRITTEN",
            key + "|emir.eu|TERMINATION",
            key + "|mifir.eu.rts22|OBJECT_WRITTEN");
  }

  @Test
  default void aHoldAppliesToEveryRowOfTheArtifact() {
    Instant firstAt = clock().now();
    Instant t = at("2030-01-01T00:00:00Z");
    RetentionClaim x1 = claim("sealed/x", "emir.eu", t);
    RetentionClaim x2 = claim("sealed/x", "mifir.eu.rts22", t);
    RetentionClaim x3 =
        claim("sealed/x", "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "sealed");
    RetentionClaim y = claim("sealed/y", "mifir.eu.rts22", t);
    retention().recordRetention(List.of(x1, x2, x3, y));
    assertThat(due()).hasSize(3);

    clock().advance(Duration.ofDays(1));
    retention().setRetentionHold(new EvidenceKey("sealed/x"), true);
    assertThat(every())
        .containsExactly(
            row(x1, true, firstAt),
            row(x3, true, firstAt),
            row(x2, true, firstAt),
            row(y, false, firstAt));
    // held rows are not due, however far the date is; the other artifact still is
    assertThat(triples(due())).containsExactly("sealed/y|mifir.eu.rts22|OBJECT_WRITTEN");

    // the same value again is not an error and changes nothing
    retention().setRetentionHold(new EvidenceKey("sealed/x"), true);
    assertThat(every())
        .extracting(RetentionRow::legalHold)
        .containsExactly(true, true, true, false);

    // the release reaches every row; the dates and the times were never touched
    retention().setRetentionHold(new EvidenceKey("sealed/x"), false);
    assertThat(every())
        .containsExactly(
            row(x1, false, firstAt),
            row(x3, false, firstAt),
            row(x2, false, firstAt),
            row(y, false, firstAt));
    assertThat(triples(due())).hasSize(3).contains("sealed/x|emir.eu|OBJECT_WRITTEN");
  }

  @Test
  default void aHoldReachesTheNamedArtifactOnlyAndNotKeysThatShareItsPrefix() {
    Instant t = at("2030-01-01T00:00:00Z");
    retention()
        .recordRetention(
            List.of(
                claim("sealed/x", "emir.eu", t),
                claim("sealed/x", "mifir.eu.rts22", t),
                claim("sealed/xy", "emir.eu", t),
                claim("sealed/x/z", "emir.eu", t),
                claim("sealed/w", "emir.eu", t)));

    retention().setRetentionHold(new EvidenceKey("sealed/x"), true);

    // the key before it and its two extensions (one past a slash, one not) are not held
    assertThat(every())
        .extracting(
            r -> r.claim().artifact().path() + "|" + r.claim().regime().id(),
            RetentionRow::legalHold)
        .containsExactly(
            tuple("sealed/w|emir.eu", false),
            tuple("sealed/x|emir.eu", true),
            tuple("sealed/x|mifir.eu.rts22", true),
            tuple("sealed/x/z|emir.eu", false),
            tuple("sealed/xy|emir.eu", false));
    assertThat(triples(due()))
        .containsExactly(
            "sealed/w|emir.eu|OBJECT_WRITTEN",
            "sealed/x/z|emir.eu|OBJECT_WRITTEN",
            "sealed/xy|emir.eu|OBJECT_WRITTEN");

    // holding the longer key and releasing the shorter one do not reach each other
    retention().setRetentionHold(new EvidenceKey("sealed/xy"), true);
    retention().setRetentionHold(new EvidenceKey("sealed/x"), false);
    assertThat(every())
        .extracting(r -> r.claim().artifact().path(), RetentionRow::legalHold)
        .containsExactly(
            tuple("sealed/w", false),
            tuple("sealed/x", false),
            tuple("sealed/x", false),
            tuple("sealed/x/z", false),
            tuple("sealed/xy", true));
  }

  /**
   * A hold and a claim for one artifact race (one thread holds, the other records a row for a new
   * regime): whichever comes first, afterwards every row of the artifact is held. Every serial
   * order ends so, so this passes on every correct adapter in every interleaving and can only fail
   * on an adapter whose hold and claim do not see each other (some rounds per run).
   */
  @Test
  default void aHoldAndANewRegimeOfOneArtifactRacingEndWithEveryRowHeld() throws Exception {
    Instant t = at("2030-01-01T00:00:00Z");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 40; round++) {
        String key = String.format(Locale.ROOT, "race/%03d", round);
        retention().recordRetention(List.of(claim(key, "emir.eu", t)));
        CountDownLatch start = new CountDownLatch(1);
        Future<?> hold =
            pool.submit(
                () -> {
                  assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                  retention().setRetentionHold(new EvidenceKey(key), true);
                  return null;
                });
        Future<?> add =
            pool.submit(
                () -> {
                  assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                  retention().recordRetention(List.of(claim(key, "mifir.eu.rts22", t)));
                  return null;
                });
        start.countDown();
        hold.get(60, TimeUnit.SECONDS);
        add.get(60, TimeUnit.SECONDS);

        assertThat(retention().retentionRows(key, Optional.empty(), 5))
            .as("round %d", round)
            .hasSize(2)
            .allMatch(RetentionRow::legalHold);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  default void aClaimRecordedAfterAHoldIsHeldToo() {
    Instant t = at("2030-01-01T00:00:00Z");
    retention().recordRetention(List.of(claim("sealed/h", "emir.eu", t)));
    retention().setRetentionHold(new EvidenceKey("sealed/h"), true);

    // a new regime, a new anchor and a merge into the existing row: all held
    retention()
        .recordRetention(
            List.of(
                claim("sealed/h", "mifir.eu.rts22", t),
                claim("sealed/h", "emir.eu", AnchorEvent.TERMINATION, Optional.of(t), "sealed"),
                claim("sealed/h", "emir.eu", t.plusSeconds(1))));
    assertThat(every()).hasSize(3).allMatch(RetentionRow::legalHold);
    assertThat(due()).isEmpty();

    // another artifact is not held by it
    retention().recordRetention(List.of(claim("sealed/k", "emir.eu", t)));
    assertThat(triples(due())).containsExactly("sealed/k|emir.eu|OBJECT_WRITTEN");

    // after the release a new row is not held
    retention().setRetentionHold(new EvidenceKey("sealed/h"), false);
    retention().recordRetention(List.of(claim("sealed/h", "sftr.eu", t)));
    assertThat(every()).noneMatch(RetentionRow::legalHold);
    assertThat(due()).hasSize(5);
  }

  @Test
  default void aHoldOnAnUnknownArtifactIsRefused() {
    for (boolean hold : List.of(true, false)) {
      assertThatThrownBy(() -> retention().setRetentionHold(new EvidenceKey("sealed/none"), hold))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(every()).isEmpty();

    // a known artifact does not make its neighbour known
    retention()
        .recordRetention(List.of(claim("sealed/known", "emir.eu", at("2030-01-01T00:00:00Z"))));
    assertThatThrownBy(() -> retention().setRetentionHold(new EvidenceKey("sealed/know"), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> retention().setRetentionHold(new EvidenceKey("sealed/known/x"), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(every()).extracting(RetentionRow::legalHold).containsExactly(false);
  }

  @Test
  default void rowsOfOneArtifactAreNeverSplitAcrossPages() {
    Instant t = at("2030-01-01T00:00:00Z");
    List<RetentionClaim> claims = new ArrayList<>();
    for (String regime : List.of("emir.eu", "mifir.eu.rts22", "sftr.eu")) {
      claims.add(claim("p/a", regime, t));
      claims.add(claim("p/b", regime, t));
    }
    claims.add(claim("p/c", "emir.eu", t));
    claims.add(claim("p/d", "emir.eu", t));
    claims.add(claim("p/d", "mifir.eu.rts22", t));
    claims.add(claim("other/z", "emir.eu", t));
    Collections.reverse(claims);
    retention().recordRetention(claims);

    // one artifact a page: three, three, one and two rows
    List<List<RetentionRow>> single = walkRetentionRows("p/", 1);
    assertThat(single).extracting(List::size).containsExactly(3, 3, 1, 2);
    assertThat(single).allSatisfy(page -> assertThat(artifacts(page)).hasSize(1));
    // two artifacts a page
    assertThat(walkRetentionRows("p/", 2)).extracting(List::size).containsExactly(6, 3);
    // a limit above the number of artifacts lists all of them
    assertThat(walkRetentionRows("p/", 4)).extracting(List::size).containsExactly(9);
    assertThat(artifacts(flatten(walkRetentionRows("p/", 3))))
        .containsExactly("p/a", "p/b", "p/c", "p/d");

    // the rows of one artifact come in the order (regime, anchor)
    assertThat(triples(retention().retentionRows("p/a", Optional.empty(), 1)))
        .containsExactly(
            "p/a|emir.eu|OBJECT_WRITTEN",
            "p/a|mifir.eu.rts22|OBJECT_WRITTEN",
            "p/a|sftr.eu|OBJECT_WRITTEN");

    // limit 0 lists nothing, a negative limit is refused
    assertThat(retention().retentionRows("p/", Optional.empty(), 0)).isEmpty();
    assertThatThrownBy(() -> retention().retentionRows("p/", Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);

    // after is an artifact, exclusive, and need not exist; a prefix need not end at a segment
    assertThat(artifacts(retention().retentionRows("p/", Optional.of(new EvidenceKey("p/a")), 10)))
        .containsExactly("p/b", "p/c", "p/d");
    assertThat(artifacts(retention().retentionRows("p/", Optional.of(new EvidenceKey("p/b0")), 10)))
        .containsExactly("p/c", "p/d");
    assertThat(artifacts(retention().retentionRows("p/", Optional.of(new EvidenceKey("a")), 10)))
        .containsExactly("p/a", "p/b", "p/c", "p/d");
    assertThat(retention().retentionRows("p/", Optional.of(new EvidenceKey("p/d")), 10)).isEmpty();
    assertThat(retention().retentionRows("p/", Optional.of(new EvidenceKey("zzz")), 10)).isEmpty();
    assertThat(artifacts(retention().retentionRows("p/b", Optional.empty(), 10)))
        .containsExactly("p/b");
    assertThat(retention().retentionRows("q/", Optional.empty(), 10)).isEmpty();
    assertThat(artifacts(every())).containsExactly("other/z", "p/a", "p/b", "p/c", "p/d");
  }

  @Test
  default void aPrefixMatchesTheStartOfAKeyAndNotItsMiddle() {
    Instant t = at("2030-01-01T00:00:00Z");
    retention()
        .recordRetention(
            List.of(
                claim("p/a", "emir.eu", t),
                claim("p/b", "emir.eu", t),
                claim("q/p/a", "emir.eu", t),
                claim("sealed/p/b", "emir.eu", t)));

    assertThat(artifacts(retention().retentionRows("p/", Optional.empty(), 10)))
        .containsExactly("p/a", "p/b");
    assertThat(artifacts(retention().retentionRows("p/", Optional.of(new EvidenceKey("p/a")), 10)))
        .containsExactly("p/b");
    assertThat(retention().retentionRows("p/", Optional.of(new EvidenceKey("p/b")), 10)).isEmpty();
    assertThat(artifacts(retention().retentionRows("q/", Optional.empty(), 10)))
        .containsExactly("q/p/a");

    // a key that is also a prefix: after is strictly after, also when it equals the prefix
    assertThat(artifacts(retention().retentionRows("p/a", Optional.empty(), 10)))
        .containsExactly("p/a");
    assertThat(retention().retentionRows("p/a", Optional.of(new EvidenceKey("p/a")), 10)).isEmpty();

    // a walk with the empty prefix advances, one artifact a page
    assertThat(walkRetentionRows("", 1)).extracting(List::size).containsExactly(1, 1, 1, 1);
    assertThat(artifacts(flatten(walkRetentionRows("", 1))))
        .containsExactly("p/a", "p/b", "q/p/a", "sealed/p/b");
  }

  /** No key has a NUL or a character outside the key alphabet, so such a prefix lists nothing. */
  @Test
  default void aPrefixThatNoArtifactCanHaveListsNothing() {
    Instant t = at("2030-01-01T00:00:00Z");
    retention()
        .recordRetention(
            List.of(
                claim("a", "emir.eu", t),
                claim("a/b", "emir.eu", t),
                claim("ord/a", "emir.eu", t),
                claim("ord/e", "emir.eu", t)));
    char nul = (char) 0;
    char eAcute = (char) 0x00E9;
    char euro = (char) 0x20AC;
    char lone = (char) 0xD800;

    for (String prefix :
        List.of(
            "a" + nul,
            String.valueOf(nul),
            "a/" + nul + "b",
            "ord/" + nul,
            "ord/" + eAcute,
            "ord/e" + eAcute,
            "ord/" + euro,
            String.valueOf(eAcute),
            "a b",
            "ord/" + lone)) {
      String shown = prefix.chars().boxed().toList().toString();
      assertThat(retention().retentionRows(prefix, Optional.empty(), 100))
          .as("prefix %s", shown)
          .isEmpty();
      assertThat(retention().retentionRows(prefix, Optional.of(new EvidenceKey("a")), 100))
          .as("prefix %s after a", shown)
          .isEmpty();
      assertThat(retention().retentionRows(prefix, Optional.empty(), 1))
          .as("prefix %s with limit 1", shown)
          .isEmpty();
    }

    assertThat(artifacts(retention().retentionRows("a", Optional.empty(), 100)))
        .containsExactly("a", "a/b");
    assertThat(artifacts(retention().retentionRows("ord/", Optional.empty(), 100)))
        .containsExactly("ord/a", "ord/e");
  }

  @Test
  default void theLimitIsCappedAtOneThousand() {
    Instant t = at("2030-01-01T00:00:00Z");
    List<RetentionClaim> claims = new ArrayList<>();
    for (int i = 0; i < 1_100; i++) {
      claims.add(claim(String.format(Locale.ROOT, "cap/%05d", i), "emir.eu", t));
    }
    retention().recordRetention(claims);

    for (int limit : List.of(5_000, Integer.MAX_VALUE)) {
      List<RetentionRow> artifactPage = retention().retentionRows("cap/", Optional.empty(), limit);
      assertThat(artifactPage).hasSize(1_000);
      assertThat(artifactPage.get(999).claim().artifact().path()).isEqualTo("cap/00999");
      List<RetentionRow> duePage =
          retention().expiredRetention(farFuture(), Optional.empty(), limit);
      assertThat(duePage).hasSize(1_000);
      assertThat(duePage.get(999).claim().artifact().path()).isEqualTo("cap/00999");
    }
    assertThat(walkRetentionRows("cap/", 5_000)).extracting(List::size).containsExactly(1_000, 100);
    assertThat(walkExpired(farFuture(), 5_000)).extracting(List::size).containsExactly(1_000, 100);
  }

  @Test
  default void expiredRetentionListsOnlyDueUnheldRowsInOrder() {
    Instant d2030 = at("2030-01-01T00:00:00Z");
    List<RetentionClaim> claims = new ArrayList<>();
    // four rows that tie on the date: the order is artifact, regime, anchor
    claims.add(claim("sealed/b", "mifir.eu.rts22", d2030));
    claims.add(claim("sealed/a", "mifir.eu.rts22", d2030));
    claims.add(claim("sealed/a", "emir.eu", AnchorEvent.TERMINATION, Optional.of(d2030), "sealed"));
    claims.add(claim("sealed/a", "emir.eu", d2030));
    // earlier than the others, and one microsecond later than the boundary
    claims.add(claim("sealed/c", "mifir.eu.rts22", at("2029-06-01T00:00:00Z")));
    claims.add(claim("sealed/d", "mifir.eu.rts22", d2030.plus(Duration.ofNanos(1_000))));
    // never listed: an open anchor, a held row
    claims.add(claim("sealed/e", "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "sealed"));
    claims.add(claim("sealed/f", "mifir.eu.rts22", at("2028-01-01T00:00:00Z")));
    claims.add(claim("sealed/g", "mifir.eu.rts22", at("2035-01-01T00:00:00Z")));
    retention().recordRetention(claims);
    retention().setRetentionHold(new EvidenceKey("sealed/f"), true);

    List<String> dueAt2030 =
        List.of(
            "sealed/c|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/a|emir.eu|OBJECT_WRITTEN",
            "sealed/a|emir.eu|TERMINATION",
            "sealed/a|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/b|mifir.eu.rts22|OBJECT_WRITTEN");
    // the date of a row that is due on the very instant is listed
    assertThat(triples(retention().expiredRetention(d2030, Optional.empty(), 100)))
        .containsExactlyElementsOf(dueAt2030);
    // one microsecond earlier, only the earliest row is due
    assertThat(
            triples(
                retention()
                    .expiredRetention(d2030.minus(Duration.ofNanos(1_000)), Optional.empty(), 100)))
        .containsExactly("sealed/c|mifir.eu.rts22|OBJECT_WRITTEN");
    // one microsecond later, the row of sealed/d joins at the end
    List<String> dueLater = new ArrayList<>(dueAt2030);
    dueLater.add("sealed/d|mifir.eu.rts22|OBJECT_WRITTEN");
    assertThat(
            triples(
                retention()
                    .expiredRetention(d2030.plus(Duration.ofNanos(1_000)), Optional.empty(), 100)))
        .containsExactlyElementsOf(dueLater);
    // nothing is due before the earliest date
    assertThat(retention().expiredRetention(at("2029-05-31T23:59:59Z"), Optional.empty(), 100))
        .isEmpty();
    // far in the future: every dated row but the held one
    assertThat(triples(due()))
        .containsExactly(
            "sealed/c|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/a|emir.eu|OBJECT_WRITTEN",
            "sealed/a|emir.eu|TERMINATION",
            "sealed/a|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/b|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/d|mifir.eu.rts22|OBJECT_WRITTEN",
            "sealed/g|mifir.eu.rts22|OBJECT_WRITTEN");

    // limit
    assertThat(triples(retention().expiredRetention(d2030, Optional.empty(), 2)))
        .containsExactlyElementsOf(dueAt2030.subList(0, 2));
    assertThat(retention().expiredRetention(d2030, Optional.empty(), 0)).isEmpty();
    assertThatThrownBy(() -> retention().expiredRetention(d2030, Optional.empty(), -1))
        .isInstanceOf(IllegalArgumentException.class);

    // after: strictly after the (date, artifact, regime, anchor) of the row, which need not be
    // stored
    List<RetentionRow> listed = retention().expiredRetention(d2030, Optional.empty(), 100);
    for (int i = 0; i < listed.size(); i++) {
      assertThat(triples(retention().expiredRetention(d2030, Optional.of(listed.get(i)), 100)))
          .as("after row %d", i)
          .containsExactlyElementsOf(dueAt2030.subList(i + 1, dueAt2030.size()));
    }
    RetentionRow before2030 =
        row(claim("sealed/zzz", "mifir.eu.rts22", d2030.minusSeconds(1)), false, clock().now());
    assertThat(triples(retention().expiredRetention(d2030, Optional.of(before2030), 100)))
        .containsExactlyElementsOf(dueAt2030.subList(1, dueAt2030.size()));
    RetentionRow insideTheTie = row(claim("sealed/a", "faa.eu", d2030), false, clock().now());
    assertThat(triples(retention().expiredRetention(d2030, Optional.of(insideTheTie), 100)))
        .containsExactlyElementsOf(dueAt2030.subList(3, dueAt2030.size()));
    // a row without a date is never in the list, so it is no position in it
    RetentionRow open =
        row(
            claim("sealed/a", "emir.eu", AnchorEvent.TERMINATION, Optional.empty(), "sealed"),
            false,
            clock().now());
    assertThatThrownBy(() -> retention().expiredRetention(d2030, Optional.of(open), 100))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  default void walkingExpiredRetentionInPagesOfThreeReturnsEveryDueRowOnce() {
    // the rows in the order of the list, built by nested loops over hand-ordered literals: the
    // first date for the artifacts of d0, the second for those of d1
    List<String> dates = List.of("2030-01-01T00:00:00Z", "2030-01-02T00:00:00Z");
    List<String> artifactKeys = List.of("walk/x1", "walk/x2", "walk/x3");
    List<String> regimes = List.of("emir.eu", "mifir.eu.rts22");
    List<AnchorEvent> anchors = List.of(AnchorEvent.OBJECT_WRITTEN, AnchorEvent.TERMINATION);
    List<RetentionClaim> claims = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    for (int d = 0; d < dates.size(); d++) {
      for (String artifact : artifactKeys) {
        String own = artifact + "d" + d;
        for (String regime : regimes) {
          for (AnchorEvent anchor : anchors) {
            claims.add(claim(own, regime, anchor, Optional.of(at(dates.get(d))), "sealed"));
            expected.add(own + "|" + regime + "|" + anchor);
          }
        }
      }
    }
    // stored in an order that is none of the sorted ones
    List<RetentionClaim> shuffled = new ArrayList<>(claims);
    Collections.reverse(shuffled);
    Collections.rotate(shuffled, 5);
    retention().recordRetention(shuffled);

    assertThat(expected).hasSize(24);
    assertThat(triples(due())).containsExactlyElementsOf(expected);
    List<List<RetentionRow>> pages = walkExpired(farFuture(), 3);
    assertThat(pages).hasSize(8).allSatisfy(page -> assertThat(page).hasSize(3));
    assertThat(triples(flatten(pages))).containsExactlyElementsOf(expected);
    // a page size that does not divide the list
    assertThat(triples(flatten(walkExpired(farFuture(), 5)))).containsExactlyElementsOf(expected);
    assertThat(walkExpired(farFuture(), 5)).extracting(List::size).containsExactly(5, 5, 5, 5, 4);
    // the first date only
    assertThat(triples(flatten(walkExpired(at("2030-01-01T00:00:00Z"), 3))))
        .containsExactlyElementsOf(expected.subList(0, 12));
  }

  @Test
  default void expiredRetentionNeverListsControlKeys() {
    Instant t = at("2030-01-01T00:00:00Z");
    retention()
        .recordRetention(
            List.of(
                claim("control/epoch-1", "mifir.eu.rts22", t),
                claim("control/a/b", "emir.eu", t),
                claim(
                    "control/epoch-1", "emir.eu", AnchorEvent.TERMINATION, Optional.of(t), "epoch"),
                // the prefix is "control/" exactly: these are not under it
                claim("controlx/a", "emir.eu", t),
                claim("Control/a", "emir.eu", t),
                claim("sealed/control/a", "emir.eu", t),
                claim("control", "emir.eu", t)));

    assertThat(triples(due()))
        .containsExactly(
            "Control/a|emir.eu|OBJECT_WRITTEN",
            "control|emir.eu|OBJECT_WRITTEN",
            "controlx/a|emir.eu|OBJECT_WRITTEN",
            "sealed/control/a|emir.eu|OBJECT_WRITTEN");
    // the rows themselves are stored and listed by retentionRows (the exemption is of the expiry
    // list)
    assertThat(artifacts(every()))
        .containsExactly(
            "Control/a",
            "control",
            "control/a/b",
            "control/epoch-1",
            "controlx/a",
            "sealed/control/a");
    assertThat(walkExpired(farFuture(), 1)).hasSize(4);
    // a hold and its release on a control key change nothing
    retention().setRetentionHold(new EvidenceKey("control/a/b"), true);
    retention().setRetentionHold(new EvidenceKey("control/a/b"), false);
    assertThat(due()).hasSize(4);
  }

  @Test
  default void anAsOfTheStoresCannotHoldIsRefused() {
    retention().recordRetention(List.of(claim("sealed/a", "emir.eu", at("2030-01-01T00:00:00Z"))));

    for (Instant bad :
        List.of(
            at("2030-01-01T00:00:00.000000001Z"),
            at("+10000-01-01T00:00:00Z"),
            at("-0001-12-31T23:59:59.999999Z"),
            Instant.MAX,
            Instant.MIN)) {
      assertThatThrownBy(() -> retention().expiredRetention(bad, Optional.empty(), 10))
          .as("asOf %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(
              e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(bad.toString()));
    }

    // the first and the last storable instant are accepted
    assertThat(retention().expiredRetention(at("0000-01-01T00:00:00Z"), Optional.empty(), 10))
        .isEmpty();
    assertThat(retention().expiredRetention(farFuture(), Optional.empty(), 10)).hasSize(1);
  }

  /**
   * Pins the validation of the record that every adapter inherits: the ledger never receives the
   * value, so the assertions that nothing was written hold by construction.
   */
  @Test
  default void aRetainUntilOutsideTheStorableRangeIsRefused() {
    Instant valid = at("2030-01-01T00:00:00Z");
    List<Instant> unstorable =
        List.of(
            at("2030-01-01T00:00:00.000000001Z"),
            at("2030-01-01T00:00:00.123456789Z"),
            at("2030-01-01T00:00:00.000000999Z"),
            at("+10000-01-01T00:00:00Z"),
            at("-0001-12-31T23:59:59Z"),
            at("-0001-12-31T23:59:59.999999Z"),
            Instant.MAX,
            Instant.MIN);
    for (Instant bad : unstorable) {
      assertThatThrownBy(
              () ->
                  retention()
                      .recordRetention(
                          List.of(
                              claim("sealed/ok", "emir.eu", valid),
                              claim("sealed/bad", "emir.eu", bad))))
          .as("retain-until %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(every()).as("retain-until %s", bad).isEmpty();
    }
    // an existing row is not touched by a refused claim
    retention().recordRetention(List.of(claim("sealed/ok", "emir.eu", valid)));
    for (Instant bad : unstorable) {
      assertThatThrownBy(
              () -> retention().recordRetention(List.of(claim("sealed/ok", "emir.eu", bad))))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(every())
        .extracting(r -> r.claim().retainUntil())
        .containsExactly(Optional.of(valid));
  }

  @Test
  default void aKeyOf512CharactersIsStored() {
    EvidenceKey longest = LedgerFixtures.maximumLengthKey();
    assertThat(longest.path()).hasSize(512);
    Instant t = at("2030-01-01T00:00:00Z");
    RetentionClaim claim =
        new RetentionClaim(
            longest,
            new Regime("mifir.eu.rts22"),
            AnchorEvent.OBJECT_WRITTEN,
            Optional.of(t),
            "sealed");
    Instant recordedAt = clock().now();
    retention().recordRetention(List.of(claim));

    assertThat(retention().retentionRows(longest.path(), Optional.empty(), 10))
        .containsExactly(row(claim, false, recordedAt));
    assertThat(every()).containsExactly(row(claim, false, recordedAt));
    assertThat(due()).containsExactly(row(claim, false, recordedAt));
    // the merge and the hold work at this length too
    retention().recordRetention(List.of(claim(longest.path(), "mifir.eu.rts22", t.plusSeconds(5))));
    retention().setRetentionHold(longest, true);
    assertThat(every())
        .extracting(r -> r.claim().retainUntil().orElseThrow(), RetentionRow::legalHold)
        .containsExactly(tuple(t.plusSeconds(5), true));
    assertThat(due()).isEmpty();
  }

  @Test
  default void aBatchWithADuplicateIsRefusedAndWritesNothing() {
    Instant t = at("2030-01-01T00:00:00Z");
    RetentionClaim stored = claim("sealed/dup", "emir.eu", t);
    retention().recordRetention(List.of(stored));
    Instant storedAt = clock().now();
    clock().advance(Duration.ofMinutes(1));

    RetentionClaim fresh = claim("sealed/fresh", "emir.eu", t);
    RetentionClaim again = claim("sealed/fresh", "emir.eu", t.plusSeconds(1));
    RetentionClaim later = claim("sealed/dup", "emir.eu", t.plusSeconds(100));
    // the same triple twice, with other dates or classes, in the middle or at the end
    for (List<RetentionClaim> call :
        List.of(
            List.of(fresh, again),
            List.of(later, fresh, later),
            List.of(
                fresh,
                claim("sealed/fresh", "emir.eu", AnchorEvent.OBJECT_WRITTEN, Optional.empty(), "x"),
                later))) {
      assertThatThrownBy(() -> retention().recordRetention(call))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(every()).containsExactly(row(stored, false, storedAt));
    }
    // the same artifact under two regimes, or two anchors, is not a duplicate
    retention()
        .recordRetention(
            List.of(
                fresh,
                claim("sealed/fresh", "mifir.eu.rts22", t),
                claim(
                    "sealed/fresh",
                    "emir.eu",
                    AnchorEvent.TERMINATION,
                    Optional.empty(),
                    "sealed")));
    assertThat(every()).hasSize(4);
  }

  @Test
  default void anEmptyListOfClaimsIsNotAnErrorAndStoresNothing() {
    retention().recordRetention(List.of());
    assertThat(every()).isEmpty();

    retention().recordRetention(List.of(claim("sealed/e", "emir.eu", at("2030-01-01T00:00:00Z"))));
    retention().recordRetention(List.of());
    assertThat(every()).hasSize(1);
  }

  /**
   * Pins the validation of the record that every adapter inherits: the ledger never receives the
   * value, so the assertions that nothing was written hold by construction.
   */
  @Test
  default void anInvalidClassNameIsRefused() {
    Instant t = at("2030-01-01T00:00:00Z");
    char eAcute = (char) 0x00E9;
    List<String> invalid =
        List.of(
            "",
            " ",
            "a b",
            "a/b",
            "a,b",
            "a=b",
            "a+b",
            "x" + eAcute,
            String.valueOf(eAcute),
            "a\nb",
            "a" + (char) 0,
            "x".repeat(101));
    for (String bad : invalid) {
      assertThatThrownBy(
              () -> claim("sealed/c", "emir.eu", AnchorEvent.OBJECT_WRITTEN, Optional.of(t), bad))
          .as("class with code points %s", bad.chars().boxed().toList())
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(every()).isEmpty();

    // the alphabet and the longest name are stored and read back as they are
    List<String> valid = List.of("sealed", "A-Z_a.z-09", "x".repeat(100), "-", ".", "_");
    for (int i = 0; i < valid.size(); i++) {
      retention()
          .recordRetention(
              List.of(
                  claim(
                      "sealed/c" + i,
                      "emir.eu",
                      AnchorEvent.OBJECT_WRITTEN,
                      Optional.of(t),
                      valid.get(i))));
    }
    assertThat(every())
        .extracting(r -> r.claim().retentionClass())
        .containsExactlyElementsOf(valid);
  }

  /**
   * D6: the artifacts differ in case and in the characters of the key alphabet, and the regimes in
   * the dot and the digit, so that a collation that is not byte order (a locale collation ignores
   * case and punctuation at first) puts them in another order. The orders below are written by
   * hand: '+' < ',' < '-' < '.' < digits < '=' < upper case < '_' < lower case, and a key before
   * its own extensions. All rows have one date, so the expiry list is ordered by artifact and
   * regime alone.
   *
   * <p>Note for a real server: this method tells byte order from another collation only when the
   * default collation of the cluster is not {@code C}; the columns are checked in the catalogue by
   * the tests of the adapter.
   */
  @Test
  default void artifactsAndRegimesAreListedInByteOrderWhateverTheirCaseAndPunctuation() {
    List<String> artifactsInByteOrder =
        List.of(
            "sealed/A",
            "sealed/B",
            "sealed/a",
            "sealed/a+b",
            "sealed/a,b",
            "sealed/a-b",
            "sealed/a.b",
            "sealed/a1",
            "sealed/a=b",
            "sealed/a_b",
            "sealed/ab");
    List<String> regimesInByteOrder = List.of("a", "a.1", "a.b", "a1", "a1.b", "aa", "ab");
    Instant t = at("2030-01-01T00:00:00Z");
    List<RetentionClaim> claims = new ArrayList<>();
    for (String artifact : artifactsInByteOrder) {
      for (String regime : regimesInByteOrder) {
        claims.add(claim(artifact, regime, t));
      }
    }
    List<String> expected = new ArrayList<>();
    for (String artifact : artifactsInByteOrder) {
      for (String regime : regimesInByteOrder) {
        expected.add(artifact + "|" + regime + "|OBJECT_WRITTEN");
      }
    }
    // stored in two calls, in an order that is none of the sorted ones
    List<RetentionClaim> shuffled = new ArrayList<>(claims);
    Collections.reverse(shuffled);
    Collections.rotate(shuffled, 11);
    retention().recordRetention(shuffled.subList(0, 40));
    retention().recordRetention(shuffled.subList(40, shuffled.size()));

    assertThat(triples(every())).containsExactlyElementsOf(expected);
    assertThat(triples(due())).containsExactlyElementsOf(expected);

    // retentionRows in pages of three artifacts: 3, 3, 3 and 2 artifacts, each with its 7 regimes
    List<List<RetentionRow>> artifactPages = walkRetentionRows("sealed/", 3);
    assertThat(artifactPages).extracting(List::size).containsExactly(21, 21, 21, 14);
    assertThat(artifacts(artifactPages.get(0))).containsExactly("sealed/A", "sealed/B", "sealed/a");
    assertThat(artifacts(artifactPages.get(1)))
        .containsExactly("sealed/a+b", "sealed/a,b", "sealed/a-b");
    assertThat(artifacts(artifactPages.get(2)))
        .containsExactly("sealed/a.b", "sealed/a1", "sealed/a=b");
    assertThat(artifacts(artifactPages.get(3))).containsExactly("sealed/a_b", "sealed/ab");
    assertThat(triples(flatten(artifactPages))).containsExactlyElementsOf(expected);

    // expiredRetention in pages of four rows, which split an artifact of seven rows
    List<List<RetentionRow>> duePages = walkExpired(farFuture(), 4);
    assertThat(duePages).hasSize(20);
    assertThat(duePages.get(0)).hasSize(4);
    assertThat(duePages.get(19)).hasSize(1);
    assertThat(triples(flatten(duePages))).containsExactlyElementsOf(expected);

    // a prefix inside the alphabet: sealed/a starts the key sealed/a and its eight extensions
    assertThat(artifacts(retention().retentionRows("sealed/a", Optional.empty(), 100)))
        .containsExactly(
            "sealed/a",
            "sealed/a+b",
            "sealed/a,b",
            "sealed/a-b",
            "sealed/a.b",
            "sealed/a1",
            "sealed/a=b",
            "sealed/a_b",
            "sealed/ab");
    // after: exclusive, in byte order, and the key need not exist
    assertThat(
            artifacts(
                retention()
                    .retentionRows("sealed/", Optional.of(new EvidenceKey("sealed/a.b")), 100)))
        .containsExactly("sealed/a1", "sealed/a=b", "sealed/a_b", "sealed/ab");
    assertThat(
            artifacts(
                retention()
                    .retentionRows("sealed/", Optional.of(new EvidenceKey("sealed/B")), 100)))
        .first()
        .isEqualTo("sealed/a");
    assertThat(
            artifacts(
                retention()
                    .retentionRows("sealed/", Optional.of(new EvidenceKey("sealed/a-")), 100)))
        .containsExactly(
            "sealed/a-b", "sealed/a.b", "sealed/a1", "sealed/a=b", "sealed/a_b", "sealed/ab");
    assertThat(
            artifacts(
                retention()
                    .retentionRows("sealed/", Optional.of(new EvidenceKey("sealed/a.")), 100)))
        .containsExactly("sealed/a.b", "sealed/a1", "sealed/a=b", "sealed/a_b", "sealed/ab");
    // after a row of the expiry list inside the tie: the next regime of the same artifact
    RetentionRow afterRow = duePages.get(0).get(3);
    assertThat(afterRow.claim().artifact().path()).isEqualTo("sealed/A");
    assertThat(afterRow.claim().regime().id()).isEqualTo("a1");
    assertThat(triples(retention().expiredRetention(farFuture(), Optional.of(afterRow), 2)))
        .containsExactly("sealed/A|a1.b|OBJECT_WRITTEN", "sealed/A|aa|OBJECT_WRITTEN");
  }

  /**
   * D3: a regime id is text, so two ids that differ only in a dot are two regimes and two rows, for
   * every adapter; the ids are lower case, so a pair that differs in case cannot occur.
   */
  @Test
  default void regimesThatDifferOnlyInPunctuationAreDifferentRows() {
    Instant t = at("2030-01-01T00:00:00Z");
    Instant t2 = at("2032-01-01T00:00:00Z");
    for (List<String> pair :
        List.of(List.of("ab", "a.b"), List.of("mifir.eu.rts22", "mifir.eurts22"))) {
      String plain = pair.get(0);
      String dotted = pair.get(1);
      String key = "sealed/regimes/" + plain;
      retention().recordRetention(List.of(claim(key, plain, t), claim(key, dotted, t2)));
      // a later date on one of them does not reach the other
      retention().recordRetention(List.of(claim(key, dotted, at("2034-01-01T00:00:00Z"))));
      List<RetentionRow> rows = retention().retentionRows(key, Optional.empty(), 10);
      assertThat(rows).hasSize(2);
      assertThat(rows)
          .extracting(r -> r.claim().regime().id(), r -> r.claim().retainUntil().orElseThrow())
          .containsExactlyInAnyOrder(tuple(plain, t), tuple(dotted, at("2034-01-01T00:00:00Z")));
    }
    assertThat(every()).hasSize(4);
    assertThat(due()).hasSize(4);
    // the ids that differ in case are not regimes
    assertThatThrownBy(() -> new Regime("AB")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Regime("Mifir.eu.rts22"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Regime("a.B")).isInstanceOf(IllegalArgumentException.class);
  }
}
