package dev.regpack.postgres;

import static dev.regpack.testkit.contract.LedgerFixtures.endpoint;
import static dev.regpack.testkit.contract.LedgerFixtures.reportBody;
import static dev.regpack.testkit.contract.LedgerFixtures.subjectKey;

import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionId;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.crypto.Envelope;
import dev.regpack.ports.crypto.Envelope.Header;
import dev.regpack.ports.crypto.Envelope.Kind;
import dev.regpack.ports.crypto.SubjectHash;
import dev.regpack.ports.evidence.EvidenceKey;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryId;
import dev.regpack.ports.ledger.LedgerStore;
import dev.regpack.ports.ledger.ReportLedger.NewVersion;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommit;
import dev.regpack.ports.ledger.ReportLedger.ReportableCommitted;
import dev.regpack.ports.ledger.ReportLedger.ReportableDecision;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.Subject;
import dev.regpack.ports.ledger.ReportLedger.SubjectId;
import dev.regpack.ports.ledger.ReportLedger.TrnProposal;
import dev.regpack.ports.ledger.ReportLedger.VersionOrigin;
import dev.regpack.ports.ledger.ReportLedger.VersionResult;
import dev.regpack.ports.ledger.RetentionLedger.RetentionClaim;
import dev.regpack.ports.ledger.RetentionLedger.RetentionRow;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectEntry;
import dev.regpack.ports.ledger.SubjectIndexLedger.SubjectRef;
import dev.regpack.ports.ledger.TrnConflictException;
import dev.regpack.ports.ledger.WithdrawalRequiredException;
import dev.regpack.testkit.clock.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The operations of the differential test of the in-memory and the PostgreSQL ledger (see {@link
 * PgInMemoryDifferentialIT}). An operation is plain data, so a sequence can be generated before it
 * runs (it never depends on what a ledger answered), run against each ledger in turn, shrunk by
 * removing operations, and printed so that it can be written down as a test. Running one gives a
 * text: the answer of the ledger, or the type and message of the exception. Two ledgers agree on an
 * operation when the two texts are equal.
 *
 * <p>Identifiers that a ledger invents (subject, delivery and decision numbers) are not compared as
 * numbers: each side names them by the order in which it first showed them ({@link Canon}). Every
 * text that depends on a value of the test is written with {@link Locale#ROOT} and nothing reads
 * the clock of the machine.
 */
final class DifferentialOps {
  private DifferentialOps() {}

  /** The most pages that a walk reads; a walk that has not ended by then is reported as such. */
  private static final int MAX_PAGES = 2_000;

  // ---------------------------------------------------------------------------------------------
  // one side of the comparison

  /** What the stored encoding of a report body is, as the ledger under test keeps it. */
  @FunctionalInterface
  interface EncodingReader {
    int encoding(SubjectId subject, long versionSeq, byte[] body);
  }

  /** The names that one side gives to the numbers its ledger invents, in order of appearance. */
  static final class Canon {
    private final Map<String, String> names = new HashMap<>();
    private final Map<Character, Integer> counts = new HashMap<>();

    private String name(char kind, long value) {
      return names.computeIfAbsent(
          kind + ":" + value, key -> kind + String.valueOf(counts.merge(kind, 1, Integer::sum)));
    }

    String subject(SubjectId id) {
      return name('s', id.value());
    }

    String delivery(DeliveryId id) {
      return name('l', id.value());
    }

    String decision(DecisionId id) {
      return name('d', id.value());
    }
  }

  /**
   * One ledger as the operations see it: the ledger in its default or its strict configuration, the
   * clock that both ledgers share, the numbering of this side and a count of what happened.
   */
  static final class Side {
    private final Function<Boolean, LedgerStore> ledgers;
    private final ManualClock clock;
    private final EncodingReader encodings;
    private final String namespace;
    private final Canon canon = new Canon();
    private final Map<String, Integer> notes = new TreeMap<>();

    /**
     * @param ledgers the ledger that requires envelope bodies (true) or accepts any body (false)
     * @param namespace the prefix of every event of this run, so that a run never meets the events
     *     of an earlier one in the same schema
     */
    Side(
        Function<Boolean, LedgerStore> ledgers,
        ManualClock clock,
        EncodingReader encodings,
        String namespace) {
      this.ledgers = ledgers;
      this.clock = clock;
      this.encodings = encodings;
      this.namespace = namespace;
    }

    LedgerStore ledger(boolean strict) {
      return ledgers.apply(strict);
    }

    LedgerStore ledger() {
      return ledger(false);
    }

    ManualClock clock() {
      return clock;
    }

    Canon canon() {
      return canon;
    }

    EncodingReader encodings() {
      return encodings;
    }

    String namespace() {
      return namespace;
    }

    void note(String what) {
      notes.merge(what, 1, Integer::sum);
    }

    Map<String, Integer> notes() {
      return notes;
    }
  }

  /** One step of a sequence. */
  interface Op {
    /** Runs the step against one side and returns what the ledger answered. */
    String run(Side side);

    /** The step as text that says everything needed to write it down again. */
    String describe();

    /** Strictly smaller steps that a shrinking run may try in its place. */
    default List<Op> simpler() {
      return List.of();
    }
  }

  /** The answer of an operation: its text, or the type and message of what it threw. */
  static String outcome(Op op, Side side) {
    try {
      return op.run(side);
    } catch (RuntimeException e) {
      side.note("refused." + op.getClass().getSimpleName());
      return "throws "
          + e.getClass().getName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the text of values

  /**
   * A text for a person and for a test: printable ASCII as it is, anything else as a code point.
   */
  static String text(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder out = new StringBuilder("\"");
    int i = 0;
    while (i < value.length()) {
      int codePoint = value.codePointAt(i);
      int run = 1;
      int next = i + Character.charCount(codePoint);
      while (next < value.length() && value.codePointAt(next) == codePoint) {
        run++;
        next += Character.charCount(codePoint);
      }
      String shown =
          codePoint >= 0x20 && codePoint < 0x7F && codePoint != '"' && codePoint != '\\'
              ? Character.toString(codePoint)
              : String.format(Locale.ROOT, "\\u{%X}", codePoint);
      out.append(run > 4 ? shown + "*" + run : shown.repeat(run));
      i = next;
    }
    return out.append('"').toString();
  }

  static String hashText(SubjectHash hash) {
    return hash == null ? "null" : hash.hex().substring(0, 4) + ".." + hash.hex().substring(60);
  }

  static String refText(SubjectRef ref) {
    return ref == null ? "null" : ref.kind() + ":" + text(ref.ref());
  }

  static String claim(RetentionClaim claim) {
    return claim == null
        ? "null"
        : text(claim.artifact().path())
            + " "
            + text(claim.regime().id())
            + " "
            + claim.anchor()
            + " until="
            + claim.retainUntil().map(Instant::toString).orElse("-")
            + " class="
            + text(claim.retentionClass());
  }

  static String row(RetentionRow row) {
    return row == null
        ? "null"
        : claim(row.claim()) + " hold=" + row.legalHold() + " at=" + row.recordedAt();
  }

  private static String rows(List<RetentionRow> rows) {
    return "["
        + rows.size()
        + "] "
        + rows.stream().map(DifferentialOps::row).collect(Collectors.joining("; "));
  }

  private static String refs(List<SubjectRef> refs) {
    return "["
        + refs.size()
        + "] "
        + refs.stream().map(DifferentialOps::refText).collect(Collectors.joining("; "));
  }

  private static <T> String list(List<T> items, Function<T, String> each) {
    return items == null
        ? "null"
        : items.stream().map(each).collect(Collectors.joining(", ", "[", "]"));
  }

  private static <T> List<List<T>> withoutEach(List<T> items) {
    List<List<T>> smaller = new ArrayList<>();
    if (items != null && items.size() > 1) {
      for (int i = 0; i < items.size(); i++) {
        List<T> copy = new ArrayList<>(items);
        copy.remove(i);
        smaller.add(copy);
      }
    }
    return smaller;
  }

  // ---------------------------------------------------------------------------------------------
  // the retention rows

  /** {@code recordRetention}; a null list and a list with a null element are steps too. */
  record RecordClaims(List<RetentionClaim> claims) implements Op {
    @Override
    public String run(Side side) {
      side.ledger().recordRetention(claims);
      side.note("record.ok");
      return "ok";
    }

    @Override
    public String describe() {
      return "recordRetention(" + list(claims, DifferentialOps::claim) + ")";
    }

    @Override
    public List<Op> simpler() {
      List<Op> smaller = new ArrayList<>();
      withoutEach(claims).forEach(each -> smaller.add(new RecordClaims(each)));
      return smaller;
    }
  }

  /** {@code setRetentionHold}. */
  record Hold(EvidenceKey artifact, boolean hold) implements Op {
    @Override
    public String run(Side side) {
      side.ledger().setRetentionHold(artifact, hold);
      side.note("hold.ok");
      return "ok";
    }

    @Override
    public String describe() {
      return "setRetentionHold("
          + (artifact == null ? "null" : text(artifact.path()))
          + ", "
          + hold
          + ")";
    }
  }

  /** {@code retentionRows}. */
  record Rows(String prefix, Optional<EvidenceKey> after, int limit) implements Op {
    @Override
    public String run(Side side) {
      List<RetentionRow> found = side.ledger().retentionRows(prefix, after, limit);
      side.note(found.isEmpty() ? "rows.empty" : "rows.nonEmpty");
      return rows(found);
    }

    @Override
    public String describe() {
      return "retentionRows("
          + text(prefix)
          + ", "
          + (after == null ? "null" : after.map(key -> text(key.path())).orElse("-"))
          + ", "
          + limit
          + ")";
    }
  }

  /** {@code retentionRows} walked in pages of {@code pageSize} artifacts, from the first. */
  record WalkRows(String prefix, int pageSize) implements Op {
    @Override
    public String run(Side side) {
      StringBuilder out = new StringBuilder();
      Optional<EvidenceKey> after = Optional.empty();
      for (int page = 0; page < MAX_PAGES; page++) {
        List<RetentionRow> found = side.ledger().retentionRows(prefix, after, pageSize);
        if (found.isEmpty()) {
          side.note(page > 1 ? "rows.walked" : "rows.shortWalk");
          return out.length() == 0 ? "no page" : out.toString();
        }
        out.append(rows(found)).append(" // ");
        after = Optional.of(found.get(found.size() - 1).claim().artifact());
      }
      return out + "(the walk does not end)";
    }

    @Override
    public String describe() {
      return "walk retentionRows(" + text(prefix) + ") in pages of " + pageSize;
    }
  }

  /** {@code expiredRetention}. */
  record Expired(Instant asOf, Optional<RetentionRow> after, int limit) implements Op {
    @Override
    public String run(Side side) {
      List<RetentionRow> found = side.ledger().expiredRetention(asOf, after, limit);
      side.note(found.isEmpty() ? "expired.empty" : "expired.nonEmpty");
      return rows(found);
    }

    @Override
    public String describe() {
      return "expiredRetention("
          + asOf
          + ", "
          + (after == null ? "null" : after.map(DifferentialOps::row).orElse("-"))
          + ", "
          + limit
          + ")";
    }
  }

  /** {@code expiredRetention} walked in pages of {@code pageSize} rows, from the first. */
  record WalkExpired(Instant asOf, int pageSize) implements Op {
    @Override
    public String run(Side side) {
      StringBuilder out = new StringBuilder();
      Optional<RetentionRow> after = Optional.empty();
      for (int page = 0; page < MAX_PAGES; page++) {
        List<RetentionRow> found = side.ledger().expiredRetention(asOf, after, pageSize);
        if (found.isEmpty()) {
          side.note(page > 1 ? "expired.walked" : "expired.shortWalk");
          return out.length() == 0 ? "no page" : out.toString();
        }
        out.append(rows(found)).append(" // ");
        after = Optional.of(found.get(found.size() - 1));
      }
      return out + "(the walk does not end)";
    }

    @Override
    public String describe() {
      return "walk expiredRetention(" + asOf + ") in pages of " + pageSize;
    }
  }

  /** Sets the clock that both ledgers read; setting it twice to one value is the same as once. */
  record SetClock(Instant to) implements Op {
    @Override
    public String run(Side side) {
      side.clock().set(to);
      return "ok";
    }

    @Override
    public String describe() {
      return "clock := " + to;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the data-subject index

  /** {@code indexSubjects}; a null list and a list with a null element are steps too. */
  record Index(List<SubjectEntry> entries) implements Op {
    @Override
    public String run(Side side) {
      side.ledger().indexSubjects(entries);
      side.note("index.ok");
      return "ok";
    }

    @Override
    public String describe() {
      return "indexSubjects("
          + list(
              entries,
              entry ->
                  entry == null ? "null" : hashText(entry.hash()) + " -> " + refText(entry.ref()))
          + ")";
    }

    @Override
    public List<Op> simpler() {
      List<Op> smaller = new ArrayList<>();
      withoutEach(entries).forEach(each -> smaller.add(new Index(each)));
      return smaller;
    }
  }

  /** {@code subjectRefs}. */
  record Refs(SubjectHash hash, Optional<SubjectRef> after, int limit) implements Op {
    @Override
    public String run(Side side) {
      List<SubjectRef> found = side.ledger().subjectRefs(hash, after, limit);
      side.note(found.isEmpty() ? "refs.empty" : "refs.nonEmpty");
      return refs(found);
    }

    @Override
    public String describe() {
      return "subjectRefs("
          + hashText(hash)
          + ", "
          + (after == null ? "null" : after.map(DifferentialOps::refText).orElse("-"))
          + ", "
          + limit
          + ")";
    }
  }

  /** {@code subjectRefs} walked in pages of {@code pageSize}, from the first. */
  record WalkRefs(SubjectHash hash, int pageSize) implements Op {
    @Override
    public String run(Side side) {
      StringBuilder out = new StringBuilder();
      Optional<SubjectRef> after = Optional.empty();
      for (int page = 0; page < MAX_PAGES; page++) {
        List<SubjectRef> found = side.ledger().subjectRefs(hash, after, pageSize);
        if (found.isEmpty()) {
          side.note(page > 1 ? "refs.walked" : "refs.shortWalk");
          return out.length() == 0 ? "no page" : out.toString();
        }
        out.append(refs(found)).append(" // ");
        after = Optional.of(found.get(found.size() - 1));
      }
      return out + "(the walk does not end)";
    }

    @Override
    public String describe() {
      return "walk subjectRefs(" + hashText(hash) + ") in pages of " + pageSize;
    }
  }

  /** {@code removeSubjectRefs}. */
  record Remove(SubjectRef ref) implements Op {
    @Override
    public String run(Side side) {
      int removed = side.ledger().removeSubjectRefs(ref);
      side.note(removed > 0 ? "remove.positive" : "remove.zero");
      return "removed " + removed;
    }

    @Override
    public String describe() {
      return "removeSubjectRefs(" + refText(ref) + ")";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // the report bodies

  /** The bodies of the tests: well-formed envelopes of several shapes and bodies that are not. */
  enum BodyKind {
    ENVELOPE,
    OBJECT_KIND,
    MINIMAL,
    MAXIMAL,
    PLAIN,
    EMPTY,
    MAGIC_ONLY,
    MAGIC_THEN_GARBAGE,
    BAD_VERSION,
    BAD_KIND,
    BAD_KEK_CHAR,
    KEK_LENGTH_ZERO,
    WRAPPED_LENGTH_ZERO,
    WRAPPED_LENGTH_TOO_LONG,
    ONE_BYTE_SHORT;

    /** The bytes of a fresh copy of this body. */
    byte[] bytes() {
      return switch (this) {
        case ENVELOPE -> EnvelopeBodyFixtures.envelope();
        case OBJECT_KIND -> sealed(Kind.OBJECT, "kek-2026", 60, 7);
        case MINIMAL -> sealed(Kind.BODY, "k", 1, 0);
        case MAXIMAL -> sealed(Kind.BODY, "k".repeat(100), 1_024, 300);
        case PLAIN -> EnvelopeBodyFixtures.plain();
        case EMPTY -> new byte[0];
        case MAGIC_ONLY -> "RPEN".getBytes(StandardCharsets.US_ASCII);
        case MAGIC_THEN_GARBAGE -> {
          byte[] out = new byte[204];
          Arrays.fill(out, (byte) 0x5a);
          System.arraycopy("RPEN".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
          yield out;
        }
        case BAD_VERSION -> changed(4, 2);
        case BAD_KIND -> changed(5, 3);
        case BAD_KEK_CHAR -> changed(7, '/');
        case KEK_LENGTH_ZERO -> changed(6, 0);
        // the envelope of the fixture has the KEK id "kek-2026" (8 bytes): its length is at 6, the
        // id at 7 to 14 and the wrapped length (60) at 15 and 16
        case WRAPPED_LENGTH_ZERO -> changed(15, 0, 16, 0);
        case WRAPPED_LENGTH_TOO_LONG -> changed(15, 4, 16, 1);
        case ONE_BYTE_SHORT -> {
          byte[] minimal = sealed(Kind.BODY, "k", 1, 0);
          yield Arrays.copyOf(minimal, minimal.length - 1);
        }
      };
    }

    /** The fixture envelope with the bytes at the given (index, value) pairs replaced. */
    private static byte[] changed(int... indexAndValue) {
      byte[] body = EnvelopeBodyFixtures.envelope();
      for (int i = 0; i < indexAndValue.length; i += 2) {
        body[indexAndValue[i]] = (byte) indexAndValue[i + 1];
      }
      return body;
    }

    private static byte[] sealed(Kind kind, String kekId, int wrappedLength, int plaintextLength) {
      byte[] wrapped = new byte[wrappedLength];
      Arrays.fill(wrapped, (byte) 3);
      byte[] dataKey = new byte[32];
      Arrays.fill(dataKey, (byte) 7);
      byte[] nonce = new byte[12];
      Arrays.fill(nonce, (byte) 9);
      byte[] plaintext = new byte[plaintextLength];
      Arrays.fill(plaintext, (byte) 'x');
      return Envelope.seal(
          new Header(kind, kekId, wrapped),
          dataKey,
          nonce,
          "context".getBytes(StandardCharsets.UTF_8),
          plaintext);
    }
  }

  /** How a decision names the head it expects. */
  enum HeadMode {
    /** The head of the chain as the ledger shows it now. */
    CURRENT,
    /** One above it: a stale or invented head. */
    AHEAD
  }

  /**
   * A version asked for a leg: {@code proposeTrn} false asks for none, which a new subject refuses.
   */
  record VersionSpec(Leg leg, BodyKind body, String action, String hash, boolean proposeTrn) {}

  /** A decision of a unit, for an event of this run, with the versions asked for it. */
  record UnitDecision(
      String regime,
      String event,
      HeadMode head,
      String digest,
      List<Leg> legs,
      List<VersionSpec> versions) {
    String describe() {
      return regime
          + " "
          + event
          + " "
          + head
          + " "
          + digest
          + " legs="
          + legs
          + " versions="
          + list(
              versions,
              each ->
                  each.leg()
                      + "/"
                      + each.body()
                      + "/"
                      + each.action()
                      + "/"
                      + each.hash()
                      + (each.proposeTrn() ? "" : "/noTrn"));
    }

    private ReportableDecision build(Side side, LedgerStore ledger) {
      String name = side.namespace() + event;
      long current =
          ledger
              .currentDecision(new Regime(regime), EventIdentity.of("LEI1", "XPAR", name))
              .map(DecisionRecord::decisionSeq)
              .orElse(0L);
      NewDecision decision =
          new NewDecision(
              reportBody(regime, name, "ctx", digest, legs.toArray(new Leg[0])),
              head == HeadMode.CURRENT ? current : current + 1);
      List<NewVersion> requested = new ArrayList<>();
      for (VersionSpec spec : versions) {
        requested.add(
            new NewVersion(
                subjectKey(regime, name, spec.leg()),
                "EXEC-" + name,
                spec.proposeTrn()
                    ? Optional.of(new TrnProposal(trn(side, regime, event, spec.leg()), "KEY-1"))
                    : Optional.empty(),
                spec.action(),
                Sha256.ofUtf8(spec.hash()),
                spec.body().bytes(),
                VersionOrigin.SOURCE,
                endpoint("EP1"),
                1));
      }
      return new ReportableDecision(decision, requested);
    }
  }

  /**
   * The TRN that every version of one subject proposes: it never conflicts with another subject.
   */
  static String trn(Side side, String regime, String event, Leg leg) {
    return "T" + side.namespace() + event + regime.charAt(0) + leg.name().charAt(0);
  }

  /** The messages of the argument errors that a ledger finds only in the course of a unit. */
  private static final List<String> FOUND_WHILE_PROCESSING =
      List.of(
          "a new subject needs a TRN",
          "the executing LEI of a subject cannot change",
          "a stored TRN cannot be replaced");

  /**
   * The system property that switches the leniency of {@link Commit} for a unit of several
   * decisions off ({@code true}), so that the difference it hides can be seen again: which of the
   * defects of a unit a ledger reports. The in-memory ledger takes the decisions in the order of
   * the request and PostgreSQL in the order of their chains, so a unit with a stale head and a
   * withdrawal reports one or the other, and {@code ReportLedger#commitReportable} leaves the
   * choice open (D33 (2)). Nothing else is lenient: the head that a head conflict carries is always
   * compared (D33 (1)).
   */
  static final String EXACT_UNITS = "regpack.differential.exactUnits";

  /**
   * The answer to a unit of several decisions that was refused in the course of the unit by a
   * withdrawal, a TRN of another subject or one of the argument errors above: the refusal is
   * compared by its kind only, see {@link #agree}.
   */
  static final String REFUSED_WHILE_PROCESSING =
      "refused while processing the decisions of the unit";

  /**
   * The start of the answer to a unit of several decisions that was refused with a head conflict:
   * the event identity and the head that the exception carries follow, and {@link #agree} compares
   * them. The exception names no regime, so "the same chain" is read as "the same event identity"
   * (the case of one event name in two regimes, both stale, is not told apart: R1).
   */
  static final String HEAD_CONFLICT = "refused with a head conflict on ";

  private static final String HEAD_AT = " head ";

  /**
   * Whether the refusal depends on the rows of the unit and so is found decision by decision: a
   * withdrawal, a TRN of another subject and the argument errors above. A head conflict is not in
   * the list, since it is compared in full.
   */
  private static boolean isFoundWhileProcessing(RuntimeException e) {
    return e instanceof WithdrawalRequiredException
        || e instanceof TrnConflictException
        || (e instanceof IllegalArgumentException
            && e.getMessage() != null
            && FOUND_WHILE_PROCESSING.contains(e.getMessage()));
  }

  /**
   * Whether the two answers of the ledgers to {@code op} are the same answer: equal texts are, and
   * so are, in a unit of several decisions, two refusals of the course of the unit unless both are
   * head conflicts on the same event identity (the exception names no regime). A unit can have
   * several defects, the port leaves open which one the ledger reports (D33 (2)), and the two
   * ledgers do not find them in the same order; but when both report the stale head of the same
   * event identity, the head they name must be the same (D33 (1)). With {@link #EXACT_UNITS} the
   * answers of a unit are never reduced to a refusal, so only equal texts agree.
   */
  static boolean agree(Op op, String inMemory, String postgres) {
    if (inMemory.equals(postgres)) {
      return true;
    }
    if (!(op instanceof Commit commit) || commit.decisions().size() < 2) {
      return false;
    }
    return isRefusedInTheUnit(inMemory)
        && isRefusedInTheUnit(postgres)
        && !(isHeadConflict(inMemory)
            && isHeadConflict(postgres)
            && eventIdentityOf(inMemory).equals(eventIdentityOf(postgres)));
  }

  private static boolean isRefusedInTheUnit(String answer) {
    return answer.equals(REFUSED_WHILE_PROCESSING) || isHeadConflict(answer);
  }

  private static boolean isHeadConflict(String answer) {
    return answer.startsWith(HEAD_CONFLICT);
  }

  /** The event identity of a head conflict answer: the text before the head. */
  private static String eventIdentityOf(String headConflict) {
    return headConflict.substring(0, headConflict.lastIndexOf(HEAD_AT));
  }

  /** {@code commitReportable} on the strict or the default ledger. */
  record Commit(boolean strict, List<UnitDecision> decisions) implements Op {
    @Override
    public String run(Side side) {
      LedgerStore ledger = side.ledger(strict);
      List<ReportableDecision> unit = new ArrayList<>();
      for (UnitDecision each : decisions) {
        unit.add(each.build(side, ledger));
      }
      List<ReportableCommitted> committed;
      try {
        committed =
            ledger.commitReportable(new ReportableCommit(unit, Optional.empty(), Optional.empty()));
      } catch (RuntimeException e) {
        if (decisions.size() == 1 || Boolean.getBoolean(EXACT_UNITS)) {
          throw e;
        }
        // a unit of several decisions can have several defects of the kinds below, and the port
        // does not say which of them a ledger reports (the in-memory ledger takes the decisions in
        // the order of the request, PostgreSQL in the order of their chains): the answer is the
        // refusal, and agree() compares two of them by their kind. A head conflict also names its
        // event identity and head, and two conflicts on the same event identity must name the same
        // head. The refusals
        // before the first write (an envelope body, a leg, an argument) are compared in full, in a
        // unit of any size
        if (e instanceof DecisionHeadConflictException conflict) {
          side.note("refused.Commit");
          return HEAD_CONFLICT
              + conflict.eventIdentity().value()
              + HEAD_AT
              + conflict.currentHeadSeq();
        }
        if (!isFoundWhileProcessing(e)) {
          throw e;
        }
        side.note("refused.Commit");
        return REFUSED_WHILE_PROCESSING;
      }
      StringBuilder out = new StringBuilder();
      for (ReportableCommitted each : committed) {
        DecisionRecord record = each.decision().record();
        out.append(each.decision().status())
            .append(' ')
            .append(side.canon().decision(record.decisionId()))
            .append(" seq=")
            .append(record.decisionSeq())
            .append(" supersedes=")
            .append(record.supersedesDecisionId().map(side.canon()::decision).orElse("-"));
        for (VersionResult version : each.versions()) {
          side.note("commit." + version.status());
          out.append(" [")
              .append(side.canon().subject(version.subject()))
              .append(' ')
              .append(version.trn())
              .append(" v")
              .append(version.versionSeq())
              .append(' ')
              .append(version.status())
              .append(' ')
              .append(version.delivery().map(side.canon()::delivery).orElse("-"))
              .append(']');
        }
        out.append("; ");
      }
      return out.toString();
    }

    @Override
    public String describe() {
      return "commitReportable("
          + (strict ? "strict" : "default")
          + ", "
          + list(decisions, UnitDecision::describe)
          + ")";
    }

    @Override
    public List<Op> simpler() {
      List<Op> smaller = new ArrayList<>();
      withoutEach(decisions).forEach(each -> smaller.add(new Commit(strict, each)));
      for (int i = 0; i < decisions.size(); i++) {
        UnitDecision decision = decisions.get(i);
        for (List<VersionSpec> fewer : withoutEach(decision.versions())) {
          List<UnitDecision> copy = new ArrayList<>(decisions);
          copy.set(
              i,
              new UnitDecision(
                  decision.regime(),
                  decision.event(),
                  decision.head(),
                  decision.digest(),
                  decision.legs(),
                  fewer));
          smaller.add(new Commit(strict, copy));
        }
      }
      return smaller;
    }
  }

  /**
   * Everything the ledger shows of one event: the chain of its decisions and, for each leg, the
   * subject, the subject found again by its TRN, its versions with their bodies and the encoding
   * that is stored with each body.
   */
  record ReadEvent(String regime, String event) implements Op {
    @Override
    public String run(Side side) {
      LedgerStore ledger = side.ledger();
      String name = side.namespace() + event;
      EventIdentity identity = EventIdentity.of("LEI1", "XPAR", name);
      StringBuilder out = new StringBuilder();
      out.append("chain=")
          .append(
              list(
                  ledger.decisionChain(new Regime(regime), identity),
                  each -> decision(side, each)));
      out.append(" current=")
          .append(
              ledger
                  .currentDecision(new Regime(regime), identity)
                  .map(each -> decision(side, each))
                  .orElse("-"));
      for (Leg leg : Leg.values()) {
        Optional<Subject> subject = ledger.subject(subjectKey(regime, name, leg));
        out.append(" | ").append(leg).append(' ');
        if (subject.isEmpty()) {
          out.append("none");
          continue;
        }
        Subject found = subject.get();
        out.append(side.canon().subject(found.id()))
            .append(" lei=")
            .append(found.executingLei().orElse("-"))
            .append(" trn=")
            .append(found.trn().orElse("-"))
            .append(" key=")
            .append(found.trnKeyId().orElse("-"))
            .append(" last=")
            .append(found.lastVersionSeq());
        if (found.trn().isPresent()) {
          out.append(" byTrn=")
              .append(
                  ledger
                      .subjectByTrn(found.executingLei().orElseThrow(), found.trn().orElseThrow())
                      .map(other -> side.canon().subject(other.id()))
                      .orElse("-"));
        }
        for (StoredVersion version : ledger.versions(found.id())) {
          byte[] body = version.body();
          int encoding = side.encodings().encoding(found.id(), version.versionSeq(), body);
          side.note("read.encoding" + encoding);
          out.append(" {v")
              .append(version.versionSeq())
              .append(' ')
              .append(side.canon().decision(version.decision()))
              .append(' ')
              .append(version.action())
              .append(' ')
              .append(version.logicalHash().hex(), 0, 8)
              .append(" body=")
              .append(body.length)
              .append(':')
              .append(Sha256.of(body).hex(), 0, 12)
              .append(" enc=")
              .append(encoding)
              .append(' ')
              .append(version.origin())
              .append(' ')
              .append(version.delivery().map(side.canon()::delivery).orElse("-"))
              .append('}');
        }
      }
      return out.toString();
    }

    private static String decision(Side side, DecisionRecord record) {
      return side.canon().decision(record.decisionId())
          + " seq="
          + record.decisionSeq()
          + " supersedes="
          + record.supersedesDecisionId().map(side.canon()::decision).orElse("-")
          + " "
          + record.body().outcome()
          + " "
          + record.body().inputDigest().hex().substring(0, 8)
          + " "
          + record.body().legs();
    }

    @Override
    public String describe() {
      return "read the event " + event + " of " + regime;
    }
  }
}
