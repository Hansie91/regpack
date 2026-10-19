package dev.regpack.ports.evidence.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.LeaseLedger.EndpointKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class JournalKeyTest {
  private static final EndpointKey EP =
      new EndpointKey("PRODUCTION", "ARM-ONE", "5493001KJTIIGC8Y1R12", "FCA");
  private static final JournalScope SCOPE = JournalScope.of(EP);
  private static final String SCOPE_TEXT = "PRODUCTION,ARM-ONE,5493001KJTIIGC8Y1R12,FCA";
  private static final long MAX_NUMBER = 999_999_999_999L;

  private static JournalKey.ClassA a(JournalType type, String subject, long ordinal) {
    return new JournalKey.ClassA(SCOPE, 3, type, subject, ordinal);
  }

  private static String path(JournalKey key) {
    return key.evidenceKey().path();
  }

  @Test
  void controlScopeIsTheFixedWordControl() {
    assertThat(JournalScope.control().value()).isEqualTo("control");
    assertThat(JournalScope.control().isControl()).isTrue();
    assertThat(SCOPE.isControl()).isFalse();
    assertThat(new JournalScope("control")).isEqualTo(JournalScope.control());
  }

  @Test
  void endpointScopeJoinsTheFourPartsWithCommas() {
    assertThat(SCOPE.value()).isEqualTo(SCOPE_TEXT);
  }

  @Test
  void aScopeTextThatIsNotControlOrFourValidPartsIsRefused() {
    for (String bad :
        List.of(
            "", "Control", "a,b,c", "a,b,c,d,e", "a,b,c,", ",b,c,d", "a b,b,c,d", "a/b,b,c,d")) {
      assertThatThrownBy(() -> new JournalScope(bad)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new JournalScope(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void anEndpointPartOutsideTheAlphabetOrOver24CharsIsRefusedAndNamesTheField() {
    List<String> bad =
        List.of("a b", "x/y", "x".repeat(25), "", "an e with an acute accent é", "a,b");
    for (String value : bad) {
      assertRefused(new EndpointKey(value, "R", "L", "A"), "environment", value);
      assertRefused(new EndpointKey("E", value, "L", "A"), "submissionRoute", value);
      assertRefused(new EndpointKey("E", "R", value, "A"), "submittingEntityLei", value);
      assertRefused(new EndpointKey("E", "R", "L", value), "destinationAuthority", value);
    }
    // the boundary: 24 characters and every allowed punctuation mark are accepted
    assertThat(JournalScope.of(new EndpointKey("x".repeat(24), "a.b", "c_d", "e-f")).value())
        .isEqualTo("x".repeat(24) + ",a.b,c_d,e-f");
  }

  private static void assertRefused(EndpointKey endpoint, String field, String value) {
    assertThatThrownBy(() -> JournalScope.of(endpoint))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field)
        .satisfies(
            e -> {
              if (!value.isEmpty()) {
                assertThat(e.getMessage()).doesNotContain(value);
              }
            });
  }

  @Test
  void distinctEndpointsGiveDistinctScopes() {
    String[] alphabet = {"a", "b", "A", "a.b", "a-b", "a_b"};
    Map<String, List<String>> seen = new HashMap<>();
    for (String p : alphabet) {
      for (String q : alphabet) {
        for (String r : alphabet) {
          for (String s : alphabet) {
            String scope = JournalScope.of(new EndpointKey(p, q, r, s)).value();
            seen.computeIfAbsent(scope, k -> new ArrayList<>())
                .add(p + "|" + q + "|" + r + "|" + s);
          }
        }
      }
    }
    assertThat(seen).hasSize(1_296);
    assertThat(seen.values()).allSatisfy(tuples -> assertThat(tuples).hasSize(1));
  }

  @Test
  void classAKeyRendersTheDocumentedPath() {
    assertThat(path(a(JournalType.DELIVERY_INTENT, "42", 1)))
        .isEqualTo(
            "journal/"
                + SCOPE_TEXT
                + "/e000000000003/a/DeliveryIntent/n00000000/000000000042/000000000001");
    String batch = "ab".repeat(32) + ":7";
    JournalKey.ClassA admitted =
        new JournalKey.ClassA(JournalScope.control(), 3, JournalType.BATCH_ADMITTED, batch, 1);
    assertThat(path(admitted))
        .isEqualTo(
            "journal/control/e000000000003/a/BatchAdmitted/x/"
                + "ab".repeat(32)
                + "=7/000000000001");
    assertThat(admitted.journalClass()).isEqualTo(JournalClass.A);
    assertThat(admitted.scope()).isEqualTo(JournalScope.control());
    assertThat(admitted.restoreEpoch()).isEqualTo(3);
  }

  @Test
  void classBKeyRendersShardAndPaddedSeq() {
    JournalKey.ClassB key = new JournalKey.ClassB(SCOPE, 3, 10_001);
    assertThat(path(key))
        .isEqualTo("journal/" + SCOPE_TEXT + "/e000000000003/b/00000001/0000000000010001");
    assertThat(path(new JournalKey.ClassB(SCOPE, 0, 1)))
        .isEqualTo("journal/" + SCOPE_TEXT + "/e000000000000/b/00000000/0000000000000001");
    assertThat(key.journalClass()).isEqualTo(JournalClass.B);
  }

  @Test
  void numericSubjectsSortNumericallyAsText() {
    long[] subjects = {1, 9, 10, 99, 100, 9_999, 10_000, 12_345_678, 99_999_999_999L};
    List<String> paths = new ArrayList<>();
    for (long s : subjects) {
      paths.add(path(a(JournalType.DELIVERY_INTENT, Long.toString(s), 1)));
    }
    List<String> sorted = new ArrayList<>(paths);
    sorted.sort(String::compareTo);
    assertThat(sorted).isEqualTo(paths);
  }

  @Test
  void ordinalsSortNumericallyAsText() {
    long[] ordinals = {1, 2, 9, 10, 999_999, 1_000_000, MAX_NUMBER};
    List<String> paths = new ArrayList<>();
    for (long o : ordinals) {
      paths.add(path(a(JournalType.DELIVERY_INTENT, "42", o)));
    }
    List<String> sorted = new ArrayList<>(paths);
    sorted.sort(String::compareTo);
    assertThat(sorted).isEqualTo(paths);
    List<String> seqPaths = new ArrayList<>();
    for (long s : new long[] {1, 9_999, 10_000, 10_001, 999_999, 1_000_000, MAX_NUMBER}) {
      seqPaths.add(path(new JournalKey.ClassB(SCOPE, 3, s)));
    }
    List<String> seqSorted = new ArrayList<>(seqPaths);
    seqSorted.sort(String::compareTo);
    assertThat(seqSorted).isEqualTo(seqPaths);
  }

  @Test
  void epochsSortNumericallyAsText() {
    List<String> paths = new ArrayList<>();
    for (long e : new long[] {0, 1, 9, 10, 11, 100, MAX_NUMBER}) {
      paths.add(path(new JournalKey.ClassA(SCOPE, e, JournalType.DELIVERY_INTENT, "42", 1)));
    }
    List<String> sorted = new ArrayList<>(paths);
    sorted.sort(String::compareTo);
    assertThat(sorted).isEqualTo(paths);
  }

  @Test
  void distinctSubjectsGiveDistinctKeys() {
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "042", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "0", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "1234567890123", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "a=7", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, ".", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "..", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "x".repeat(101), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "a b", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(path(a(JournalType.DELIVERY_INTENT, "42", 1))).contains("/000000000042/");
    assertThat(path(a(JournalType.DELIVERY_INTENT, "a:7", 1))).contains("/x/a=7/");
    assertThat(path(a(JournalType.DELIVERY_INTENT, "x".repeat(100), 1))).contains("/x/xxxx");
    assertThat(path(a(JournalType.DELIVERY_INTENT, "123456789012", 1)))
        .contains("/n12345678/123456789012/");

    SplittableRandom random = new SplittableRandom(20_261_019L);
    JournalType[] types = {JournalType.DELIVERY_INTENT, JournalType.RECOVERY_DECISION};
    List<JournalKey> keys = new ArrayList<>();
    while (keys.size() < 2_000) {
      try {
        keys.add(
            new JournalKey.ClassA(
                SCOPE,
                random.nextInt(2),
                types[random.nextInt(types.length)],
                randomSubject(random),
                1 + random.nextInt(3)));
      } catch (IllegalArgumentException refused) {
        // an invalid tuple: skipped
      }
    }
    for (int i = 0; i + 1 < keys.size(); i += 2) {
      JournalKey x = keys.get(i);
      JournalKey y = keys.get(i + 1);
      assertThat(x.evidenceKey().equals(y.evidenceKey())).isEqualTo(x.equals(y));
    }
    // and across the whole sample: equal keys exactly for equal tuples
    Map<EvidenceKey, JournalKey> byKey = new HashMap<>();
    for (JournalKey key : keys) {
      JournalKey earlier = byKey.putIfAbsent(key.evidenceKey(), key);
      if (earlier != null) {
        assertThat(earlier).isEqualTo(key);
      }
    }
  }

  private static String randomSubject(SplittableRandom random) {
    String alphabet = "0123456789:a.-";
    int length = 1 + random.nextInt(4);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  @Test
  void parseIsTheInverseOfEvidenceKey() {
    SplittableRandom random = new SplittableRandom(20_261_020L);
    JournalType[] aTypes = {
      JournalType.DELIVERY_INTENT, JournalType.RECOVERY_DECISION, JournalType.OPERATOR_ACTION
    };
    int made = 0;
    while (made < 2_000) {
      JournalKey key;
      try {
        if (random.nextBoolean()) {
          String subject =
              random.nextInt(3) == 0
                  ? Long.toString(1 + random.nextLong(MAX_NUMBER))
                  : randomSubject(random);
          key =
              new JournalKey.ClassA(
                  random.nextBoolean() ? SCOPE : JournalScope.control(),
                  random.nextLong(MAX_NUMBER + 1),
                  random.nextInt(8) == 0 ? JournalType.BATCH_ADMITTED : aTypes[random.nextInt(3)],
                  subject,
                  1 + random.nextLong(MAX_NUMBER));
        } else {
          key =
              new JournalKey.ClassB(
                  SCOPE, random.nextLong(MAX_NUMBER + 1), 1 + random.nextLong(MAX_NUMBER));
        }
      } catch (IllegalArgumentException refused) {
        continue; // an invalid combination (for example BatchAdmitted outside control)
      }
      assertThat(JournalKey.parse(key.evidenceKey())).isEqualTo(key);
      made++;
    }
  }

  @Test
  void parseRefusesKeysThatAreNotJournalKeysOrNotCanonical() {
    String good = path(a(JournalType.DELIVERY_INTENT, "42", 1));
    assertThat(JournalKey.parse(new EvidenceKey(good)))
        .isEqualTo(a(JournalType.DELIVERY_INTENT, "42", 1));
    for (String bad :
        List.of(
            "landing/ab/1",
            "journal",
            "journal/" + SCOPE_TEXT,
            good + "/extra",
            good.replace("n00000000", "n00000001"),
            good.replace("/000000000042/", "/42/"),
            good.replace("/000000000001", "/1"),
            good.replace("e000000000003", "e3"),
            good.replace("/a/", "/c/"),
            good.replace("DeliveryIntent", "Unknown"),
            good.replace("/n00000000/", "/x/"),
            "journal/bad scope".replace(' ', '_') + "/e000000000003/b/00000000/0000000000000001",
            "journal/" + SCOPE_TEXT + "/e000000000003/b/00000001/0000000000000001",
            "journal/" + SCOPE_TEXT + "/e000000000003/b/00000000/1",
            "journal/" + SCOPE_TEXT + "/e000000000003/b/00000000/0000000000000000",
            "journal/control/e000000000003/b/00000000/0000000000000001",
            "journal/"
                + SCOPE_TEXT
                + "/e000000000003/a/DeliveryIntent/n00000000/000000000000/000000000001")) {
      assertThatThrownBy(() -> JournalKey.parse(new EvidenceKey(bad)))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void everyRenderedKeyIsAValidEvidenceKeyUnder512Chars() {
    JournalScope longest =
        JournalScope.of(
            new EndpointKey("e".repeat(24), "r".repeat(24), "l".repeat(24), "a".repeat(24)));
    assertThat(longest.value()).hasSize(99);
    JournalKey.ClassA key =
        new JournalKey.ClassA(
            longest, MAX_NUMBER, JournalType.RECOVERY_DECISION, "a:".repeat(50), MAX_NUMBER);
    EvidenceKey evidence = key.evidenceKey();
    assertThat(evidence.path()).hasSize(256);
    for (String segment : evidence.path().split("/", -1)) {
      assertThat(segment.length()).isLessThanOrEqualTo(100);
    }
    for (JournalType type : JournalType.values()) {
      if (type.allows(JournalClass.A) && type != JournalType.BATCH_ADMITTED) {
        assertThat(
                new JournalKey.ClassA(longest, MAX_NUMBER, type, "a".repeat(100), MAX_NUMBER)
                    .evidenceKey()
                    .path()
                    .length())
            .isLessThanOrEqualTo(256);
      }
    }
    assertThat(new JournalKey.ClassB(longest, MAX_NUMBER, MAX_NUMBER).evidenceKey().path().length())
        .isLessThan(256);
  }

  @Test
  void controlScopeOnlyForBatchAdmittedAndOperatorActionAndRequiredForBatchAdmitted() {
    JournalScope control = JournalScope.control();
    assertThatThrownBy(
            () -> new JournalKey.ClassA(control, 3, JournalType.DELIVERY_INTENT, "42", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new JournalKey.ClassA(control, 3, JournalType.RECOVERY_DECISION, "42", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JournalKey.ClassA(SCOPE, 3, JournalType.BATCH_ADMITTED, "b:1", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new JournalKey.ClassA(control, 3, JournalType.BATCH_ADMITTED, "b:1", 1).scope())
        .isEqualTo(control);
    assertThat(new JournalKey.ClassA(control, 3, JournalType.OPERATOR_ACTION, "p:1", 1).scope())
        .isEqualTo(control);
    assertThat(new JournalKey.ClassA(SCOPE, 3, JournalType.OPERATOR_ACTION, "p:1", 1).scope())
        .isEqualTo(SCOPE);
  }

  @Test
  void classBRefusesControlScope() {
    assertThatThrownBy(() -> new JournalKey.ClassB(JournalScope.control(), 3, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void typeMustAllowTheClass() {
    for (JournalType type :
        List.of(
            JournalType.TRANSPORT_RESULT,
            JournalType.FEEDBACK_RECEIVED,
            JournalType.VERSION_ASSIGNED)) {
      assertThatThrownBy(() -> a(type, "42", 1)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(JournalType.DELIVERY_INTENT.allows(JournalClass.A)).isTrue();
    assertThat(JournalType.DELIVERY_INTENT.allows(JournalClass.B)).isFalse();
    assertThat(JournalType.BATCH_ADMITTED.allows(JournalClass.A)).isTrue();
    assertThat(JournalType.BATCH_ADMITTED.allows(JournalClass.B)).isFalse();
    assertThat(JournalType.RECOVERY_DECISION.allows(JournalClass.A)).isTrue();
    assertThat(JournalType.RECOVERY_DECISION.allows(JournalClass.B)).isTrue();
    assertThat(JournalType.OPERATOR_ACTION.allows(JournalClass.A)).isTrue();
    assertThat(JournalType.OPERATOR_ACTION.allows(JournalClass.B)).isTrue();
    for (JournalType type :
        List.of(
            JournalType.TRANSPORT_RESULT,
            JournalType.FEEDBACK_RECEIVED,
            JournalType.VERSION_ASSIGNED)) {
      assertThat(type.allows(JournalClass.A)).isFalse();
      assertThat(type.allows(JournalClass.B)).isTrue();
    }
  }

  @Test
  void wireNamesAreTheSpecsAndRoundTrip() {
    assertThat(JournalType.values()).hasSize(7);
    Map<JournalType, String> expected = new java.util.EnumMap<>(JournalType.class);
    expected.put(JournalType.DELIVERY_INTENT, "DeliveryIntent");
    expected.put(JournalType.BATCH_ADMITTED, "BatchAdmitted");
    expected.put(JournalType.TRANSPORT_RESULT, "TransportResult");
    expected.put(JournalType.FEEDBACK_RECEIVED, "FeedbackReceived");
    expected.put(JournalType.RECOVERY_DECISION, "RecoveryDecision");
    expected.put(JournalType.OPERATOR_ACTION, "OperatorAction");
    expected.put(JournalType.VERSION_ASSIGNED, "VersionAssigned");
    expected.forEach(
        (type, wire) -> {
          assertThat(type.wireName()).isEqualTo(wire);
          assertThat(JournalType.ofWire(wire)).isEqualTo(type);
        });
    for (String bad : List.of("", "deliveryintent", "DELIVERY_INTENT", "Nope")) {
      assertThatThrownBy(() -> JournalType.ofWire(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Nope"));
    }
    assertThat(JournalClass.values()).containsExactly(JournalClass.A, JournalClass.B);
  }

  @Test
  void ordinalSeqAndEpochBounds() {
    for (long bad : new long[] {0, -1, MAX_NUMBER + 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
      assertThatThrownBy(() -> a(JournalType.DELIVERY_INTENT, "42", bad))
          .as("ordinal %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new JournalKey.ClassB(SCOPE, 3, bad))
          .as("seq %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (long bad : new long[] {-1, MAX_NUMBER + 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
      assertThatThrownBy(
              () -> new JournalKey.ClassA(SCOPE, bad, JournalType.DELIVERY_INTENT, "42", 1))
          .as("epoch %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new JournalKey.ClassB(SCOPE, bad, 1))
          .as("epoch %s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    // the first restore epoch of the ledger is 0 and the largest value has twelve digits
    assertThat(path(new JournalKey.ClassA(SCOPE, 0, JournalType.DELIVERY_INTENT, "42", MAX_NUMBER)))
        .contains("/e000000000000/")
        .endsWith("/999999999999");
    assertThat(path(new JournalKey.ClassB(SCOPE, MAX_NUMBER, MAX_NUMBER)))
        .contains("/e999999999999/b/99999999/0000999999999999");
  }
}
