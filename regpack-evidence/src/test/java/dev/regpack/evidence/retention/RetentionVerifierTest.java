package dev.regpack.evidence.retention;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.regpack.evidence.crypto.EncryptedFixtures;
import dev.regpack.evidence.retention.RetentionVerifier.Finding;
import dev.regpack.evidence.retention.RetentionVerifier.Kind;
import dev.regpack.inmemory.InMemoryEvidenceStore;
import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.Regime;
import dev.regpack.ports.crypto.DataShreddedException;
import dev.regpack.ports.evidence.EvidenceCorruptionException;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.evidence.EvidenceStore;
import dev.regpack.ports.evidence.EvidenceStore.Retention;
import dev.regpack.ports.evidence.EvidenceStore.RetentionClass;
import dev.regpack.ports.evidence.EvidenceUnavailableException;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.testkit.clock.ManualClock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P3c1 T9, KF14: {@link RetentionVerifier} compares each retention row with the store. A store that
 * retains for a shorter time than a row says, a hold that a row has and the store lost, and a hold
 * the store has and no row records are reported; a store that retains longer, an open anchor and an
 * object that is gone (that is {@code verify}'s MISSING) are not.
 */
class RetentionVerifierTest {
  private static final Instant START = Instant.parse("2026-10-20T08:00:00Z");
  private static final Regime MIFIR = new Regime("mifir.eu.rts22");
  private static final Regime EMIR = new Regime("emir.eu.refit");
  private static final Instant Y2030 = Instant.parse("2030-10-20T08:00:00Z");
  private static final Instant Y2031 = Instant.parse("2031-10-20T08:00:00Z");
  private static final Instant Y2032 = Instant.parse("2032-10-20T08:00:00Z");

  private ManualClock clock;
  private InMemoryLedgerStore rows;
  private InMemoryEvidenceStore store;
  private RetentionVerifier verifier;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(START);
    rows = new InMemoryLedgerStore(clock);
    store = new InMemoryEvidenceStore(clock);
    verifier = new RetentionVerifier(store, rows);
  }

  private static EvidenceKey key(String name) {
    return EvidenceKey.of("sealed", name.substring(0, 2), name);
  }

  private static void put(EvidenceStore into, EvidenceKey key, Instant retainUntil) {
    into.put(
        new EvidenceStore.PutRequest(
            key,
            ("bytes of " + key.path()).getBytes(UTF_8),
            "application/octet-stream",
            new Retention(new RetentionClass("sealed"), retainUntil)));
  }

  private void row(EvidenceKey key, Regime regime, AnchorEvent anchor, Optional<Instant> until) {
    rows.recordRetention(List.of(new RetentionClaim(key, regime, anchor, until, "sealed")));
  }

  private void row(EvidenceKey key, Regime regime, Instant until) {
    row(key, regime, AnchorEvent.OBJECT_WRITTEN, Optional.of(until));
  }

  @Test
  void aShortenedRetentionAndAHoldMismatchAreReported() {
    // the store retains for less than the row says, and the row is held but the store is not
    EvidenceKey shorter = key("aa0001");
    put(store, shorter, Y2030);
    row(shorter, MIFIR, Y2032);
    rows.setRetentionHold(shorter, true);
    // the store is held and no row of the artifact is
    EvidenceKey heldByTheStoreOnly = key("bb0002");
    put(store, heldByTheStoreOnly, Y2032);
    row(heldByTheStoreOnly, MIFIR, Y2032);
    store.setLegalHold(heldByTheStoreOnly, true);
    // a whole artifact between them that agrees
    EvidenceKey agreeing = key("cc0003");
    put(store, agreeing, Y2032);
    row(agreeing, MIFIR, Y2032);

    List<Finding> findings = verifier.verify("");

    assertThat(findings)
        .extracting(Finding::kind, finding -> finding.artifact().path())
        .containsExactly(
            tuple(Kind.RETAIN_UNTIL_SHORTER, shorter.path()),
            tuple(Kind.HOLD_MISSING, shorter.path()),
            tuple(Kind.HOLD_NOT_RECORDED, heldByTheStoreOnly.path()));
    assertThat(findings)
        .extracting(Finding::detail)
        .containsExactly(
            RetentionVerifier.DETAIL_RETAIN_UNTIL_SHORTER,
            RetentionVerifier.DETAIL_HOLD_MISSING,
            RetentionVerifier.DETAIL_HOLD_NOT_RECORDED);
    // fixed texts: no date, no regime, no key
    for (Finding finding : findings) {
      assertThat(finding.detail())
          .doesNotContain("20")
          .doesNotContain(MIFIR.id())
          .doesNotContain("sealed");
    }
  }

  @Test
  void aCleanTierHasNoFinding() {
    EvidenceKey a = key("aa0001");
    put(store, a, Y2032);
    row(a, MIFIR, Y2032);
    EvidenceKey b = key("bb0002");
    put(store, b, Y2032);
    row(b, MIFIR, Y2031);
    row(b, EMIR, Y2032);
    rows.setRetentionHold(b, true);
    store.setLegalHold(b, true);
    assertThat(rows.retentionRows("", Optional.empty(), 100)).as("not vacuous").hasSize(3);

    assertThat(verifier.verify("")).isEmpty();
  }

  @Test
  void aStoreThatRetainsLongerAnOpenAnchorAndAnAbsentObjectAreNotReported() {
    EvidenceKey longer = key("aa0001");
    put(store, longer, Y2032);
    row(longer, MIFIR, Y2030);
    EvidenceKey open = key("bb0002");
    put(store, open, Y2030);
    row(open, EMIR, AnchorEvent.TERMINATION, Optional.empty());
    EvidenceKey gone = key("cc0003");
    row(gone, MIFIR, Y2032);

    assertThat(verifier.verify("")).isEmpty();
  }

  @Test
  void oneFindingPerArtifactAndKindWhateverTheNumberOfRows() {
    EvidenceKey key = key("aa0001");
    put(store, key, Y2030);
    row(key, EMIR, Y2031);
    row(key, MIFIR, Y2032);
    row(key, MIFIR, AnchorEvent.TERMINATION, Optional.empty());
    rows.setRetentionHold(key, true);

    List<Finding> findings = verifier.verify("");

    assertThat(findings)
        .extracting(Finding::kind)
        .containsExactly(Kind.RETAIN_UNTIL_SHORTER, Kind.HOLD_MISSING);
  }

  @Test
  void theLatestRowDecidesNotTheFirst() {
    EvidenceKey key = key("aa0001");
    put(store, key, Y2031);
    // the rows of an artifact come in the order of their regimes: "emir" before "mifir", so the
    // earlier date is the first row and the later one is the last
    row(key, EMIR, Y2030);
    assertThat(verifier.verify("")).as("the store covers the only row").isEmpty();

    row(key, MIFIR, Y2032);

    assertThat(verifier.verify(""))
        .extracting(Finding::kind)
        .containsExactly(Kind.RETAIN_UNTIL_SHORTER);
  }

  @Test
  void aHoldOfTheStoreThatNoRowRecordsIsReportedUntilTheRowsAreHeld() {
    // the store is held (an administrative act that did not reach the rows, or the reverse order
    // of a hold that crashed); setting the hold on the rows closes the finding
    EvidenceKey key = key("aa0001");
    put(store, key, Y2032);
    row(key, MIFIR, Y2032);
    store.setLegalHold(key, true);

    assertThat(verifier.verify(""))
        .extracting(Finding::kind)
        .containsExactly(Kind.HOLD_NOT_RECORDED);

    rows.setRetentionHold(key, true);
    assertThat(verifier.verify("")).isEmpty();
  }

  @Test
  void thePrefixSelectsTheArtifactsThatAreChecked() {
    EvidenceKey sealedOne = key("aa0001");
    put(store, sealedOne, Y2030);
    row(sealedOne, MIFIR, Y2032);
    EvidenceKey landing = EvidenceKey.of("landing", "x1");
    put(store, landing, Y2030);
    row(landing, MIFIR, Y2032);

    assertThat(verifier.verify("sealed/"))
        .extracting(finding -> finding.artifact().path())
        .containsExactly(sealedOne.path());
    assertThat(verifier.verify("landing/"))
        .extracting(finding -> finding.artifact().path())
        .containsExactly(landing.path());
    assertThat(verifier.verify("")).hasSize(2);
    assertThat(verifier.verify("journal/")).isEmpty();
  }

  @Test
  void aFindingBeyondThePageSizeIsFound() {
    // more artifacts than one page of rows, the mismatch on the last page
    int count = 1_205;
    List<EvidenceKey> keys = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      EvidenceKey key = key(String.format(Locale.ROOT, "%02d%06d", i % 100, i));
      keys.add(key);
      put(store, key, Y2032);
    }
    rows.recordRetention(claims(keys.subList(0, 500), Y2032));
    rows.recordRetention(claims(keys.subList(500, 1_000), Y2032));
    rows.recordRetention(claims(keys.subList(1_000, count), Y2032));
    EvidenceKey late = key("zz9999");
    put(store, late, Y2030);
    row(late, MIFIR, Y2032);

    List<Finding> findings = verifier.verify("");

    assertThat(findings)
        .extracting(finding -> finding.artifact().path())
        .containsExactly(late.path());
  }

  private static List<RetentionClaim> claims(List<EvidenceKey> keys, Instant until) {
    List<RetentionClaim> claims = new ArrayList<>();
    for (EvidenceKey key : keys) {
      claims.add(
          new RetentionClaim(key, MIFIR, AnchorEvent.OBJECT_WRITTEN, Optional.of(until), "sealed"));
    }
    return claims;
  }

  @Test
  void aShreddedObjectIsNotJudgedAndStopsNoRun() {
    // over the encrypted tier a destroyed KEK makes the object unreadable; its stored retention is
    // not knowable through the port, and that is neither an outage nor a finding. The objects of
    // other KEKs are still judged
    EncryptedFixtures fixtures = EncryptedFixtures.create();
    InMemoryEvidenceStore inner = new InMemoryEvidenceStore(clock);
    EvidenceStore encrypted = fixtures.encryptPerYear(inner, clock);
    EvidenceKey of2026 = key("aa0001");
    put(encrypted, of2026, Y2030);
    row(of2026, MIFIR, Y2032);
    clock.set(Instant.parse("2027-03-01T00:00:00Z"));
    EvidenceKey of2027 = key("bb0002");
    put(encrypted, of2027, Y2030);
    row(of2027, MIFIR, Y2032);
    fixtures.destroy("kek-2027");
    assertThatThrownBy(() -> encrypted.get(of2027)).isInstanceOf(DataShreddedException.class);

    List<Finding> findings = new RetentionVerifier(encrypted, rows).verify("");

    assertThat(findings)
        .extracting(finding -> finding.artifact().path(), Finding::kind)
        .containsExactly(tuple(of2026.path(), Kind.RETAIN_UNTIL_SHORTER));
  }

  @Test
  void anOutageOfTheStoreAbortsTheRun() {
    EvidenceKey key = key("aa0001");
    put(store, key, Y2030);
    row(key, MIFIR, Y2032);
    store.simulateOutage(true);

    assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(EvidenceUnavailableException.class);
  }

  @Test
  void aCorruptionAlarmOfTheStoreIsNotSwallowed() {
    EvidenceKey key = key("aa0001");
    put(store, key, Y2030);
    row(key, MIFIR, Y2032);
    EvidenceStore damaged =
        (EvidenceStore)
            Proxy.newProxyInstance(
                EvidenceStore.class.getClassLoader(),
                new Class<?>[] {EvidenceStore.class},
                (proxy, method, args) -> {
                  try {
                    if (method.getName().equals("get")) {
                      throw new EvidenceCorruptionException("the retention record is damaged");
                    }
                    return method.invoke(store, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });

    assertThatThrownBy(() -> new RetentionVerifier(damaged, rows).verify(""))
        .isInstanceOf(EvidenceCorruptionException.class);
  }

  @Test
  void nullArgumentsAreRefused() {
    assertThatThrownBy(() -> new RetentionVerifier(null, rows))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetentionVerifier(store, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Finding(null, key("aa0001"), "x"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Finding(Kind.HOLD_MISSING, null, "x"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Finding(Kind.HOLD_MISSING, key("aa0001"), null))
        .isInstanceOf(NullPointerException.class);
  }
}
