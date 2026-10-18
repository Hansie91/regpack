package dev.regpack.spikes.g;

import dev.regpack.inmemory.InMemoryLedgerStore;
import dev.regpack.model.EventIdentity;
import dev.regpack.model.Leg;
import dev.regpack.model.Outcome;
import dev.regpack.model.ReasonCode;
import dev.regpack.model.Regime;
import dev.regpack.model.Sha256;
import dev.regpack.model.decision.DecisionBody;
import dev.regpack.model.decision.DecisionRecord;
import dev.regpack.ports.Clock;
import dev.regpack.ports.ledger.DecisionHeadConflictException;
import dev.regpack.ports.ledger.DecisionLedger;
import dev.regpack.ports.ledger.DecisionLedger.BatchCommit;
import dev.regpack.ports.ledger.DecisionLedger.CommitStatus;
import dev.regpack.ports.ledger.DecisionLedger.Committed;
import dev.regpack.ports.ledger.DecisionLedger.NewDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Criterion G-4 of SP-G: the decision chains of one execution under two regimes (MiFIR and EMIR)
 * coexist on the P0 in-memory ledger, and the modelled {@code (regime, event_identity, leg)} keys
 * of {@code report_subject} behave regime-scoped. The chains run on the real ledger port; the
 * {@code report_subject} and {@code trn_assignment} rules are modelled in {@link ReportSubjects},
 * because P0 has no such table (the SQL-level form is criterion L-12 of SP-L). Disposable spike
 * code.
 */
public final class CoexistenceCheck {
  /** The check of the two chains: both start at seq 1 and advance independently. */
  public static final String CHAINS_ADVANCE_INDEPENDENTLY =
      "chains-start-at-seq-1-and-advance-independently";

  /** The check that superseding one regime's decision does not touch the other chain. */
  public static final String SUPERSEDING_LEAVES_OTHER_REGIME =
      "superseding-one-regime-leaves-the-other-chain-at-seq-1";

  /** The check of the modelled {@code report_subject} keys. */
  public static final String SUBJECT_KEYS_REGIME_SCOPED = "report-subject-keys-are-regime-scoped";

  /** The check of the TRN rules. */
  public static final String TRN_RULES = "emir-rows-carry-no-trn-and-trn-is-unique-per-lei";

  /** Every check, in the order they run. */
  public static final List<String> CHECKS =
      List.of(
          CHAINS_ADVANCE_INDEPENDENTLY,
          SUPERSEDING_LEAVES_OTHER_REGIME,
          SUBJECT_KEYS_REGIME_SCOPED,
          TRN_RULES);

  private static final String MIFIR = "mifir.eu.rts22";
  private static final String EMIR = "emir.eu";
  private static final Instant EVALUATED = Instant.parse("2026-10-09T10:00:00Z");
  private static final Instant AS_AT = Instant.parse("2026-10-09T09:30:00Z");
  private static final Clock CLOCK =
      new Clock() {
        @Override
        public Instant now() {
          return EVALUATED;
        }

        @Override
        public long monotonicNanos() {
          return 0;
        }
      };

  private CoexistenceCheck() {}

  /**
   * The outcome of a run.
   *
   * @param checks the number of checks that ran
   * @param failures the number of checks that failed
   * @param failed one entry per failed check: its name, then the reason after {@code ": "}
   */
  public record Result(int checks, int failures, List<String> failed) {
    public Result {
      failed = List.copyOf(failed);
    }
  }

  /** A thrown assertion of a check; anything else a check throws is also a failure. */
  private static final class CheckFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckFailed(String message) {
      super(message);
    }
  }

  /**
   * The modelled key rules of {@code report_subject} and {@code trn_assignment}: a report subject
   * is {@code (regime, event_identity, leg)}; a regime that has a TRN profile (MiFIR) carries a TRN
   * that is unique per (executing LEI, TRN); every other regime carries none (EMIR takes the UTI
   * from the source). A {@link Flaw} makes the model wrong on purpose, so that the check is proven
   * to detect it.
   */
  public static final class ReportSubjects {
    /** Deliberate faults for the tests; {@code NONE} is the model under test. */
    public enum Flaw {
      NONE,
      GLOBAL_SUBJECT_KEY,
      ACCEPT_DUPLICATE_SUBJECT,
      EMIR_TRN_ALLOWED,
      TRN_REUSE_ALLOWED,
      TRN_UNIQUE_GLOBALLY
    }

    /** What the model says about one report row. */
    public enum Verdict {
      ACCEPTED,
      DUPLICATE_SUBJECT,
      TRN_NOT_ALLOWED,
      TRN_REUSED
    }

    /** One report row: its subject, the executing LEI and the TRN (none for EMIR). */
    public record Row(
        Regime regime,
        EventIdentity eventIdentity,
        Leg leg,
        String executingLei,
        Optional<String> trn) {
      public Row {
        Objects.requireNonNull(regime, "regime");
        Objects.requireNonNull(eventIdentity, "eventIdentity");
        Objects.requireNonNull(leg, "leg");
        Objects.requireNonNull(executingLei, "executingLei");
        Objects.requireNonNull(trn, "trn");
      }
    }

    private final Flaw flaw;
    private final Set<List<Object>> subjects = new HashSet<>();
    private final Set<List<String>> trns = new HashSet<>();

    public ReportSubjects(Flaw flaw) {
      this.flaw = Objects.requireNonNull(flaw, "flaw");
    }

    /** Offers a row; a rejected row leaves no trace. */
    public Verdict offer(Row row) {
      List<Object> subject =
          flaw == Flaw.GLOBAL_SUBJECT_KEY
              ? List.of(row.eventIdentity(), row.leg())
              : List.of(row.regime(), row.eventIdentity(), row.leg());
      if (subjects.contains(subject) && flaw != Flaw.ACCEPT_DUPLICATE_SUBJECT) {
        return Verdict.DUPLICATE_SUBJECT;
      }
      boolean trnRegime = row.regime().id().equals(MIFIR);
      if (row.trn().isPresent() && !trnRegime && flaw != Flaw.EMIR_TRN_ALLOWED) {
        return Verdict.TRN_NOT_ALLOWED;
      }
      List<String> trnKey = null;
      if (row.trn().isPresent()) {
        trnKey =
            flaw == Flaw.TRN_UNIQUE_GLOBALLY
                ? List.of(row.trn().get())
                : List.of(row.executingLei(), row.trn().get());
        if (trns.contains(trnKey) && flaw != Flaw.TRN_REUSE_ALLOWED) {
          return Verdict.TRN_REUSED;
        }
      }
      subjects.add(subject);
      if (trnKey != null) {
        trns.add(trnKey);
      }
      return Verdict.ACCEPTED;
    }
  }

  /** Fresh in-memory ledgers (the P0 adapter), one per check. */
  public static Supplier<DecisionLedger> inMemoryLedgers() {
    return () -> new InMemoryLedgerStore(CLOCK);
  }

  /** Runs every check against the P0 in-memory ledger and the correct key model. */
  public static Result run() {
    return run(inMemoryLedgers(), ReportSubjects.Flaw.NONE);
  }

  /**
   * Runs every check.
   *
   * @param ledgers a fresh, empty ledger for each call
   * @param flaw the key model: {@code NONE} for the real run; a fault proves the check has teeth
   */
  public static Result run(Supplier<? extends DecisionLedger> ledgers, ReportSubjects.Flaw flaw) {
    List<String> failed = new ArrayList<>();
    attempt(CHAINS_ADVANCE_INDEPENDENTLY, () -> chains(ledgers.get()), failed);
    attempt(SUPERSEDING_LEAVES_OTHER_REGIME, () -> superseding(ledgers.get()), failed);
    attempt(SUBJECT_KEYS_REGIME_SCOPED, () -> subjectKeys(flaw), failed);
    attempt(TRN_RULES, () -> trnRules(flaw), failed);
    return new Result(CHECKS.size(), failed.size(), failed);
  }

  private static void attempt(String name, Runnable check, List<String> failed) {
    try {
      check.run();
    } catch (CheckFailed e) {
      failed.add(name + ": " + e.getMessage());
    } catch (RuntimeException e) {
      failed.add(name + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new CheckFailed(message);
    }
  }

  // ---------------------------------------------------------------- the ledger checks

  private static final EventIdentity EXECUTION = EventIdentity.of("LEI1", "XPAR", "E1");

  private static DecisionBody body(String regime, String context, String digest) {
    return new DecisionBody(
        EXECUTION,
        new Regime(regime),
        Sha256.ofUtf8(context),
        AS_AT,
        EVALUATED,
        Outcome.REPORT,
        List.of(),
        new ReasonCode("SYN.COEXISTENCE.CHECK"),
        "N00",
        List.of(),
        List.of(),
        List.of(),
        List.of(Leg.MARKET),
        Sha256.ofUtf8(digest));
  }

  private static DecisionRecord commit(
      DecisionLedger ledger, DecisionBody body, long expectedHeadSeq) {
    List<Committed> committed =
        ledger.commitDecisions(
            new BatchCommit(List.of(new NewDecision(body, expectedHeadSeq)), Optional.empty()));
    require(committed.size() == 1, "one decision committed, got " + committed.size());
    require(
        committed.get(0).status() == CommitStatus.INSERTED,
        body.regime().id() + " seq " + (expectedHeadSeq + 1) + " is a new row");
    return committed.get(0).record();
  }

  private static long head(DecisionLedger ledger, String regime) {
    return ledger
        .currentDecision(new Regime(regime), EXECUTION)
        .map(DecisionRecord::decisionSeq)
        .orElse(0L);
  }

  private static void chains(DecisionLedger ledger) {
    DecisionRecord m1 = commit(ledger, body(MIFIR, "ctx-mifir-1", "d1"), 0);
    DecisionRecord e1 = commit(ledger, body(EMIR, "ctx-emir-1", "d1"), 0);
    require(m1.decisionSeq() == 1, "the MiFIR chain starts at seq 1, was " + m1.decisionSeq());
    require(e1.decisionSeq() == 1, "the EMIR chain starts at seq 1, was " + e1.decisionSeq());

    // compare-and-set on the EMIR chain only
    DecisionRecord e2 = commit(ledger, body(EMIR, "ctx-emir-2", "d2"), 1);
    require(e2.decisionSeq() == 2, "EMIR advances to seq 2, was " + e2.decisionSeq());
    require(
        e2.supersedesDecisionId().equals(Optional.of(e1.decisionId())),
        "EMIR seq 2 supersedes EMIR seq 1");
    require(head(ledger, MIFIR) == 1, "the MiFIR head is still 1 after an EMIR advance");

    // a stale expected head on the MiFIR chain is refused with the MiFIR head, then CAS succeeds
    try {
      commit(ledger, body(MIFIR, "ctx-mifir-stale", "dS"), 0);
      throw new CheckFailed("a stale expected head (0) on the MiFIR chain must be refused");
    } catch (DecisionHeadConflictException conflict) {
      require(
          conflict.currentHeadSeq() == 1,
          "the conflict reports the MiFIR head 1, not " + conflict.currentHeadSeq());
    }
    DecisionRecord m2 = commit(ledger, body(MIFIR, "ctx-mifir-2", "d2"), 1);
    require(m2.decisionSeq() == 2, "MiFIR advances to seq 2, was " + m2.decisionSeq());
    require(
        m2.supersedesDecisionId().equals(Optional.of(m1.decisionId())),
        "MiFIR seq 2 supersedes MiFIR seq 1");
    require(
        ledger.decisionChain(new Regime(MIFIR), EXECUTION).size() == 2,
        "the MiFIR chain holds two decisions");
    require(
        ledger.decisionChain(new Regime(EMIR), EXECUTION).size() == 2,
        "the EMIR chain holds two decisions");
  }

  private static void superseding(DecisionLedger ledger) {
    DecisionRecord m1 = commit(ledger, body(MIFIR, "ctx-mifir-1", "d1"), 0);
    DecisionRecord e1 = commit(ledger, body(EMIR, "ctx-emir-1", "d1"), 0);
    DecisionRecord m2 = commit(ledger, body(MIFIR, "ctx-mifir-2", "d2"), 1);
    require(m2.decisionSeq() == 2, "the MiFIR decision is superseded at seq 2");
    require(
        m2.supersedesDecisionId().equals(Optional.of(m1.decisionId())),
        "the new MiFIR decision supersedes the first one");
    require(head(ledger, EMIR) == 1, "the EMIR chain stays at seq 1");
    require(
        ledger.currentDecision(new Regime(EMIR), EXECUTION).equals(Optional.of(e1)),
        "the current EMIR decision is the first one, unchanged");
    require(
        ledger.decisionChain(new Regime(EMIR), EXECUTION).equals(List.of(e1)),
        "the EMIR chain holds exactly its first decision");
    require(
        ledger.decisionChain(new Regime(MIFIR), EXECUTION).equals(List.of(m1, m2)),
        "the MiFIR chain holds both of its decisions");
  }

  // ---------------------------------------------------------------- the modelled key checks

  private static ReportSubjects.Row row(
      String regime, String event, Leg leg, String lei, String trn) {
    return new ReportSubjects.Row(
        new Regime(regime),
        EventIdentity.of("LEI1", "XPAR", event),
        leg,
        lei,
        Optional.ofNullable(trn));
  }

  private static void expect(
      ReportSubjects.Verdict expected, ReportSubjects model, ReportSubjects.Row row, String why) {
    ReportSubjects.Verdict actual = model.offer(row);
    require(actual == expected, why + ": expected " + expected + ", was " + actual);
  }

  private static void subjectKeys(ReportSubjects.Flaw flaw) {
    ReportSubjects model = new ReportSubjects(flaw);
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(MIFIR, "E1", Leg.MARKET, "LEI-A", "TRN-1"),
        "the MiFIR subject of the execution and leg");
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(EMIR, "E1", Leg.MARKET, "LEI-A", null),
        "the same event identity and leg under the second regime");
    expect(
        ReportSubjects.Verdict.DUPLICATE_SUBJECT,
        model,
        row(MIFIR, "E1", Leg.MARKET, "LEI-A", "TRN-2"),
        "a duplicate subject under one regime");
    expect(
        ReportSubjects.Verdict.DUPLICATE_SUBJECT,
        model,
        row(EMIR, "E1", Leg.MARKET, "LEI-A", null),
        "a duplicate subject under the other regime");
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(MIFIR, "E1", Leg.CLIENT, "LEI-A", "TRN-3"),
        "another leg of the same event is another subject");
  }

  private static void trnRules(ReportSubjects.Flaw flaw) {
    ReportSubjects model = new ReportSubjects(flaw);
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(EMIR, "E1", Leg.MARKET, "LEI-A", null),
        "an EMIR row without a TRN");
    expect(
        ReportSubjects.Verdict.TRN_NOT_ALLOWED,
        model,
        row(EMIR, "E2", Leg.MARKET, "LEI-A", "TRN-X"),
        "an EMIR row that carries a TRN");
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(MIFIR, "E3", Leg.MARKET, "LEI-A", "TRN-1"),
        "a MiFIR row with a fresh TRN");
    expect(
        ReportSubjects.Verdict.TRN_REUSED,
        model,
        row(MIFIR, "E4", Leg.MARKET, "LEI-A", "TRN-1"),
        "a reused (executing LEI, TRN)");
    expect(
        ReportSubjects.Verdict.ACCEPTED,
        model,
        row(MIFIR, "E5", Leg.MARKET, "LEI-B", "TRN-1"),
        "the same TRN under another executing LEI");
  }
}
