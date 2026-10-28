package dev.regpack.postgres;

import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.RetentionLedger.AnchorEvent;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.ports.ledger.SubjectIndexLedger.RefKind;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import dev.regpack.postgres.DifferentialOps.BodyKind;
import dev.regpack.postgres.DifferentialOps.Commit;
import dev.regpack.postgres.DifferentialOps.Expired;
import dev.regpack.postgres.DifferentialOps.HeadMode;
import dev.regpack.postgres.DifferentialOps.Hold;
import dev.regpack.postgres.DifferentialOps.Index;
import dev.regpack.postgres.DifferentialOps.Op;
import dev.regpack.postgres.DifferentialOps.ReadEvent;
import dev.regpack.postgres.DifferentialOps.RecordClaims;
import dev.regpack.postgres.DifferentialOps.Refs;
import dev.regpack.postgres.DifferentialOps.Remove;
import dev.regpack.postgres.DifferentialOps.Rows;
import dev.regpack.postgres.DifferentialOps.SetClock;
import dev.regpack.postgres.DifferentialOps.UnitDecision;
import dev.regpack.postgres.DifferentialOps.VersionSpec;
import dev.regpack.postgres.DifferentialOps.WalkExpired;
import dev.regpack.postgres.DifferentialOps.WalkRefs;
import dev.regpack.postgres.DifferentialOps.WalkRows;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * The random sequences of the differential test: the same seed gives the same sequences on every
 * machine that runs the same Java version ({@link SplittableRandom} is a specified algorithm), and
 * nothing here reads the clock or the locale. This class is the only place of the test that draws
 * random numbers. The values come from small pools, so that operations meet each other's rows:
 * artifacts and regimes that are prefixes of each other or differ in case and punctuation, dates at
 * the ends of the storable range and one microsecond from them, references whose UTF-16 order is
 * not their UTF-8 order, the longest key, regime, class and reference, and a few values that no
 * ledger may accept (a prefix that no key can have, a date it cannot hold, a negative limit, a
 * null).
 */
final class DifferentialGenerator {
  /** The 512 characters of the longest evidence key, see {@code LedgerFixtures}. */
  private static final String LONGEST_KEY =
      String.join(
          "/",
          "Ab0=,+-._".repeat(11) + "z",
          "Ab0=,+-._".repeat(11) + "z",
          "Ab0=,+-._".repeat(11) + "z",
          "Ab0=,+-._".repeat(11) + "z",
          "Ab0=,+-._".repeat(11) + "z",
          "Ab0=,+-");

  /** Artifacts: the first eight are used most often. */
  static final List<EvidenceKey> ARTIFACTS =
      Stream.of(
              "sealed/a",
              "sealed/a/b",
              "sealed/a.b",
              "sealed/a-b",
              "sealed/ab",
              "journal/j1",
              "control/e1",
              "x",
              "sealed/a=b",
              "sealed/a,b",
              "sealed/a+b",
              "sealed/a_b",
              "sealed/a1",
              "sealed/A",
              "sealed/B",
              "sealed/a/B",
              "control/e2/x",
              "control",
              "controlx/y",
              "Control/e",
              "landing/q",
              "sealed/" + "q".repeat(100),
              LONGEST_KEY)
          .map(EvidenceKey::new)
          .toList();

  /** Keys that nothing stores, for the position after which to list. */
  private static final List<EvidenceKey> OTHER_KEYS =
      Stream.of(
              "sealed",
              "sealed/0",
              "sealed/a.a",
              "sealed/a/b/c",
              "sealed/zzz",
              "A",
              "0",
              "zzz",
              "control/e1/x")
          .map(EvidenceKey::new)
          .toList();

  /** Prefixes: some keys, parts of keys, a prefix that no key can have, and the empty one. */
  private static final List<String> PREFIXES =
      List.of(
          "",
          "s",
          "sealed",
          "sealed/",
          "sealed/a",
          "sealed/a/",
          "sealed/a.",
          "sealed/a-",
          "sealed/ab",
          "sealed/A",
          "journal",
          "control/",
          "control",
          "x",
          "sealed/zzz",
          "sealed/a%",
          "sealed_",
          "sealed/a'",
          "sealed/a\\",
          "sealed/" + "q".repeat(100),
          LONGEST_KEY,
          LONGEST_KEY + "z",
          "a".repeat(5_000),
          "\0",
          "sealed/\0",
          "sealed/a\0",
          character(0xE9),
          "sealed/" + character(0xE9),
          character(0xE000),
          character(0xFFFF),
          character(0x1F600),
          String.valueOf((char) 0xD800),
          String.valueOf((char) 0xDC00),
          "sealed/" + (char) 0xD83D,
          "sealed/a" + (char) 0x7F);

  /**
   * Regimes: ids that are prefixes of each other and differ in punctuation; the first three most.
   */
  private static final List<Regime> REGIMES =
      Stream.of(
              "mifir.eu.rts22",
              "emir.eu",
              "a",
              "a.1",
              "a.b",
              "a1",
              "a1.b",
              "aa",
              "ab",
              "z",
              "r" + "a".repeat(127))
          .map(Regime::new)
          .toList();

  private static final List<String> CLASSES =
      List.of("sealed", "journal", "epoch", "landing", "Class.1_x-y", "c", "k".repeat(100));

  /** Dates for a retain-until and for the clock: the ends of the range, one microsecond off. */
  static final List<Instant> DATES =
      Stream.of(
              "0000-01-01T00:00:00Z",
              "0000-01-01T00:00:00.000001Z",
              "0000-12-31T23:59:59.999999Z",
              "0001-01-01T00:00:00Z",
              "1969-12-31T23:59:59.999999Z",
              "1970-01-01T00:00:00Z",
              "2026-10-09T07:59:59.999999Z",
              "2026-10-09T08:00:00Z",
              "2026-10-09T08:00:00.000001Z",
              "2026-10-09T09:00:00Z",
              "2027-01-01T00:00:00Z",
              "2030-01-01T00:00:00Z",
              "2038-01-19T03:14:07Z",
              "5000-01-01T00:00:00Z",
              "9999-12-31T23:59:58Z",
              "9999-12-31T23:59:59.999998Z",
              "9999-12-31T23:59:59.999999Z")
          .map(Instant::parse)
          .toList();

  /** The seconds of 0000-01-01T00:00:00Z and of 10000-01-01T00:00:00Z. */
  private static final long FIRST_SECOND = -62_167_219_200L;

  private static final long END_SECOND = 253_402_300_800L;

  /**
   * Instants that a ledger must refuse as an {@code asOf}: finer than a microsecond or off range.
   */
  private static final List<Instant> UNSTORABLE =
      List.of(
          Instant.parse("2030-01-01T00:00:00.000000001Z"),
          Instant.parse("9999-12-31T23:59:59.9999995Z"),
          Instant.ofEpochSecond(FIRST_SECOND, 500),
          Instant.ofEpochSecond(FIRST_SECOND - 1, 999_999_000),
          Instant.ofEpochSecond(END_SECOND),
          Instant.ofEpochSecond(END_SECOND, 1_000),
          Instant.MIN,
          Instant.MAX);

  static final List<SubjectHash> HASHES =
      Stream.of(
              "0".repeat(63) + "1",
              "0".repeat(63) + "2",
              "f".repeat(64),
              "8" + "0".repeat(63),
              "7" + "f".repeat(63),
              "0123456789abcdef".repeat(4),
              "c".repeat(64))
          .map(SubjectHash::new)
          .toList();

  /** References: the first twelve are used most often. */
  private static final List<String> REFS =
      List.of(
          "r/a",
          "r/A",
          "r/B",
          "r/a+b",
          "r/a,b",
          "r/a-b",
          "r/a.b",
          "r/a1",
          "r/a=b",
          "r/a_b",
          "r/ab",
          "r/" + character(0xE000),
          "r/" + character(0xFFFD),
          "r/" + character(0xFFFF),
          "r/" + character(0xFFFE),
          "r/" + character(0xF8FF),
          "r/" + character(0x10000),
          "r/" + character(0x1F600),
          "r/" + character(0x10FFFF),
          "r/" + character(0xFFFF) + character(0x10000),
          "r/" + character(0x10000) + character(0xFFFF),
          character(0xE9),
          character(0xC9),
          "e" + character(0x301),
          character(0xDF),
          character(0x130),
          character(0x131),
          "i",
          "I",
          "r/a b",
          "r/ a",
          "r/%",
          "r/_",
          "r/\\",
          "r/'",
          "r/\"",
          "r/;--",
          character(0x80),
          character(0x9F),
          character(0xA0),
          character(0x2028),
          character(0xFEFF),
          "a",
          "aa",
          "r/" + "a".repeat(510),
          "a".repeat(512),
          character(0xFFFF).repeat(512),
          character(0x1F600).repeat(256));

  private static final int BASIC_REFS = 12;

  private static final List<String> ACTIONS = List.of("NEWT", "NEWT", "AMND", "CANC");

  private final SplittableRandom random;

  DifferentialGenerator(long seed) {
    this.random = new SplittableRandom(seed);
  }

  private static String character(int codePoint) {
    return Character.toString(codePoint);
  }

  /** The next sequence of {@code length} operations. */
  List<Op> sequence(int length) {
    List<Op> ops = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      ops.add(next());
    }
    return ops;
  }

  // ---------------------------------------------------------------------------------------------

  private Op next() {
    int roll = random.nextInt(100);
    if (roll < 20) {
      return recordClaims();
    }
    if (roll < 25) {
      return new Hold(chance(2) ? null : pick(ARTIFACTS, 8), chance(60));
    }
    if (roll < 36) {
      return rows();
    }
    if (roll < 39) {
      return new WalkRows(pick(PREFIXES, 8), pageSize());
    }
    if (roll < 48) {
      return expired();
    }
    if (roll < 51) {
      return new WalkExpired(pick(DATES, 17), pageSize());
    }
    if (roll < 54) {
      return new SetClock(clockValue());
    }
    if (roll < 66) {
      return index();
    }
    if (roll < 74) {
      return refs();
    }
    if (roll < 77) {
      return new WalkRefs(pick(HASHES, 4), pageSize());
    }
    if (roll < 82) {
      return new Remove(chance(1) ? null : ref());
    }
    if (roll < 92) {
      return commit();
    }
    return new ReadEvent(regime(), event());
  }

  private boolean chance(int percent) {
    return random.nextInt(100) < percent;
  }

  /** An element of the list, the first {@code hot} of them half of the time. */
  private <T> T pick(List<T> items, int hot) {
    int bound = chance(50) ? Math.min(hot, items.size()) : items.size();
    return items.get(random.nextInt(bound));
  }

  private int pageSize() {
    return chance(10) ? 1 : 2 + random.nextInt(4);
  }

  private int limit() {
    int roll = random.nextInt(100);
    if (roll < 6) {
      return 0;
    }
    if (roll < 18) {
      return 1;
    }
    if (roll < 30) {
      return 2;
    }
    if (roll < 40) {
      return 3;
    }
    if (roll < 46) {
      return 5;
    }
    if (roll < 62) {
      return 10;
    }
    if (roll < 82) {
      return 1_000;
    }
    if (roll < 88) {
      return 1_001;
    }
    if (roll < 94) {
      return -1;
    }
    if (roll < 96) {
      return Integer.MIN_VALUE;
    }
    return Integer.MAX_VALUE;
  }

  private Instant clockValue() {
    if (chance(8)) {
      return pick(DATES, 17);
    }
    Instant start = Instant.parse("2026-10-09T08:00:00Z");
    return start.plusNanos(1_000L * random.nextInt(1_000)).plusSeconds(random.nextInt(4) * 86_400L);
  }

  // ---------------------------------------------------------------------------------------------
  // retention

  private RetentionClaim claim() {
    return new RetentionClaim(
        pick(ARTIFACTS, 8),
        pick(REGIMES, 3),
        chance(50) ? AnchorEvent.OBJECT_WRITTEN : AnchorEvent.TERMINATION,
        chance(12) ? Optional.empty() : Optional.of(pick(DATES, 17)),
        pick(CLASSES, 3));
  }

  /** Many claims with distinct (artifact, regime, anchor), for one call that writes a batch. */
  private Op bulkClaims() {
    List<RetentionClaim> claims = new ArrayList<>();
    Set<List<Object>> keys = new HashSet<>();
    int size = 15 + random.nextInt(26);
    for (int attempts = 0; claims.size() < size && attempts < 10 * size; attempts++) {
      RetentionClaim claim = claim();
      if (keys.add(List.of(claim.artifact(), claim.regime(), claim.anchor()))) {
        claims.add(claim);
      }
    }
    return new RecordClaims(claims);
  }

  private Op recordClaims() {
    int roll = random.nextInt(100);
    if (roll < 2) {
      return new RecordClaims(null);
    }
    if (roll < 5) {
      return bulkClaims();
    }
    int size = roll < 8 ? 0 : 1 + random.nextInt(4);
    List<RetentionClaim> claims = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      claims.add(claim());
    }
    if (size > 1 && chance(8)) {
      // two claims of one call for one (artifact, regime, anchor)
      RetentionClaim first = claims.get(0);
      claims.add(
          random.nextInt(size),
          new RetentionClaim(
              first.artifact(),
              first.regime(),
              first.anchor(),
              Optional.of(pick(DATES, 17)),
              pick(CLASSES, 3)));
    }
    if (size > 0 && chance(3)) {
      claims.add(random.nextInt(size + 1), null);
    }
    return new RecordClaims(claims);
  }

  private Op rows() {
    String prefix = chance(1) ? null : pick(PREFIXES, 8);
    Optional<EvidenceKey> after = Optional.empty();
    if (chance(1)) {
      after = null;
    } else if (chance(45)) {
      after = Optional.of(afterKey(prefix));
    }
    return new Rows(prefix, after, limit());
  }

  /** A position after which to list: often the prefix itself, when that is a key. */
  private EvidenceKey afterKey(String prefix) {
    if (prefix != null && chance(30)) {
      try {
        return new EvidenceKey(prefix);
      } catch (IllegalArgumentException notAKey) {
        // an invalid key falls through to the pools
      }
    }
    return chance(25) ? pick(OTHER_KEYS, OTHER_KEYS.size()) : pick(ARTIFACTS, 8);
  }

  private Op expired() {
    Instant asOf;
    if (chance(1)) {
      asOf = null;
    } else if (chance(10)) {
      asOf = pick(UNSTORABLE, UNSTORABLE.size());
    } else {
      asOf = pick(DATES, 17);
    }
    Optional<RetentionRow> after = Optional.empty();
    if (chance(1)) {
      after = null;
    } else if (chance(50)) {
      after = Optional.of(new RetentionRow(claim(), chance(50), pick(DATES, 17)));
    }
    return new Expired(asOf, after, limit());
  }

  // ---------------------------------------------------------------------------------------------
  // subject index

  private SubjectRef ref() {
    return new SubjectRef(
        chance(50) ? RefKind.REPORT_VERSION : RefKind.EVIDENCE_OBJECT, pick(REFS, BASIC_REFS));
  }

  private Op index() {
    int roll = random.nextInt(100);
    if (roll < 2) {
      return new Index(null);
    }
    int size = roll < 8 ? 0 : roll < 11 ? 20 + random.nextInt(30) : 1 + random.nextInt(5);
    List<SubjectEntry> entries = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      entries.add(new SubjectEntry(pick(HASHES, 4), ref()));
    }
    if (size > 0 && chance(10)) {
      // the same entry twice in one call
      entries.add(random.nextInt(size), entries.get(random.nextInt(size)));
    }
    if (size > 0 && chance(2)) {
      entries.add(random.nextInt(size + 1), null);
    }
    return new Index(entries);
  }

  private Op refs() {
    Optional<SubjectRef> after = chance(45) ? Optional.of(ref()) : Optional.empty();
    if (chance(1)) {
      after = null;
    }
    return new Refs(chance(1) ? null : pick(HASHES, 4), after, limit());
  }

  // ---------------------------------------------------------------------------------------------
  // report bodies

  private String regime() {
    return chance(80) ? "mifir.eu.rts22" : "emir.eu";
  }

  private String event() {
    return "E" + (1 + random.nextInt(3));
  }

  private BodyKind body() {
    int roll = random.nextInt(100);
    if (roll < 28) {
      return BodyKind.ENVELOPE;
    }
    if (roll < 48) {
      return BodyKind.PLAIN;
    }
    return BodyKind.values()[random.nextInt(BodyKind.values().length)];
  }

  private List<Leg> legs() {
    int roll = random.nextInt(100);
    if (roll < 50) {
      return List.of(Leg.MARKET);
    }
    if (roll < 62) {
      return List.of(Leg.CLIENT);
    }
    if (roll < 95) {
      return List.of(Leg.MARKET, Leg.CLIENT);
    }
    return List.of(Leg.ALLOCATION);
  }

  private UnitDecision unitDecision() {
    List<Leg> legs = legs();
    List<VersionSpec> versions = new ArrayList<>();
    if (!chance(3)) {
      for (Leg leg : legs) {
        versions.add(version(leg));
      }
    }
    if (chance(5)) {
      // a version for a leg that the decision does not have, or a second one for the same leg
      versions.add(version(chance(50) ? Leg.ALLOCATION : legs.get(0)));
    }
    return new UnitDecision(
        regime(),
        event(),
        chance(12) ? HeadMode.AHEAD : HeadMode.CURRENT,
        "d" + (1 + random.nextInt(2)),
        legs,
        versions);
  }

  private VersionSpec version(Leg leg) {
    return new VersionSpec(
        leg, body(), pick(ACTIONS, ACTIONS.size()), "h" + (1 + random.nextInt(3)), !chance(4));
  }

  private Op commit() {
    List<UnitDecision> decisions = new ArrayList<>();
    decisions.add(unitDecision());
    if (chance(25)) {
      decisions.add(unitDecision());
    }
    return new Commit(chance(50), decisions);
  }
}
