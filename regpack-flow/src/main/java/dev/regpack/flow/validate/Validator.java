package dev.regpack.flow.validate;

import dev.regpack.engine.pack.CanonicalBindings;
import dev.regpack.model.Leg;
import dev.regpack.model.Regime;
import dev.regpack.model.canonical.CanonicalExecution;
import dev.regpack.model.canonical.LifecycleKind;
import dev.regpack.model.ident.Currency;
import dev.regpack.model.ident.Isin;
import dev.regpack.model.ident.Lei;
import dev.regpack.model.ident.Mic;
import dev.regpack.pack.report.ValidationRules;
import dev.regpack.pack.report.ValidationRules.Check;
import dev.regpack.pack.report.ValidationRules.Disposition;
import dev.regpack.pack.report.ValidationRules.Rule;
import dev.regpack.pack.report.ValidationRules.Severity;
import dev.regpack.ports.Clock;
import dev.regpack.ports.expr.Value;
import dev.regpack.ports.expr.ValueType;
import dev.regpack.ports.ledger.DeliveryLedger;
import dev.regpack.ports.ledger.DeliveryLedger.DeliveryState;
import dev.regpack.ports.ledger.ReportLedger;
import dev.regpack.ports.ledger.ReportLedger.StoredVersion;
import dev.regpack.ports.ledger.ReportLedger.SubjectKey;
import dev.regpack.ports.refdata.ReferenceData;
import dev.regpack.ports.refdata.ReferenceData.Anchor;
import dev.regpack.ports.refdata.ReferenceData.AsOf;
import dev.regpack.ports.refdata.ReferenceData.DatasetId;
import dev.regpack.ports.refdata.ReferenceData.Entry;
import dev.regpack.ports.refdata.ReferenceData.SnapshotInfo;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validation layers L1 to L5 over one canonical execution (plan W1a, task T9, spec 8.1). The rules
 * come from the pack ({@link ValidationRules}); this class holds the code of each check of the
 * closed vocabulary and nothing about any regime: the field names, the datasets, the limits and the
 * action names all come from the rule arguments.
 *
 * <p>Rules run in file order. A failing check gives a {@link Finding} with the severity of its
 * rule. A BLOCKER with the disposition QUARANTINE fails the record; a WARNING adds its code to the
 * flags and the record passes; an INFO only adds the finding. One rule gives at most one finding
 * per code. A finding holds no value of the record.
 *
 * <p>A field name is a canonical path (bound by {@link CanonicalBindings}), or {@code report.} and
 * the name of a derived field, which is read from the map given to {@link #validate}. A field that
 * is absent (an optional path, or a derived field that the report does not have) is skipped by the
 * check that names it; a name that is neither bound nor of that form, and a field of a type the
 * check cannot read, are refused when the validator is built.
 *
 * <p>Reference lookups are anchored at the trade date, in the current snapshot of each dataset,
 * read at validation time. A snapshot older than its maximum age adds the flag {@code
 * SNAPSHOT_STALE} and still validates. The time of receipt is the instant of the clock at the call.
 * The chain check reads committed state only (the report ledger and the delivery ledger). A dataset
 * that is not loaded is a defect: the call throws {@link IllegalArgumentException} and the record
 * is not taken as valid.
 *
 * <p><b>Only the LEIs are a closed world.</b> The lookups of the executing, client and counterparty
 * LEI (V09, V10) fail a record whose LEI no seed lists, so a real LEI is stopped. An ISIN that is
 * not listed is only flagged (V11, a WARNING {@code REFDATA_UNKNOWN} or {@code
 * REFDATA_LAG_EXPECTED}) and the record is still reported; and the MIC seed holds two real MICs
 * (XPAR and XAMS), so a trade on either passes V12. Nothing here stops real ISINs or a real trade
 * on those two venues: V11 and V12 are not a real-data stop, and neither is any other rule of this
 * class.
 */
public final class Validator {
  /**
   * The result for one execution.
   *
   * @param pass false if a BLOCKER with the disposition QUARANTINE failed
   * @param findings the findings in rule order
   * @param flags the codes of the warnings and the flag {@code SNAPSHOT_STALE}, each once, in the
   *     order they arose
   */
  public record ValidationResult(boolean pass, List<Finding> findings, List<String> flags) {
    public ValidationResult {
      findings = List.copyOf(findings);
      flags = List.copyOf(flags);
    }
  }

  /** One-line flaws for the mutant tests of the stage (plan Convention 3). */
  enum Flaw {
    NONE,
    /** A report whose delivery is rejected, in repair, withdrawn or written off counts as live. */
    DEAD_COUNTS_AS_LIVE,
    /** A BLOCKER does not fail the record. */
    BLOCKER_PASSES,
    /** A WARNING fails the record. */
    WARNING_FAILS_RECORD,
    /** The age limit is exclusive: a trade exactly at the limit is too old. */
    AGE_LIMIT_EXCLUSIVE,
    /** A snapshot older than its maximum age raises no flag. */
    NO_STALE_FLAG,
    /** Lookups are anchored at the date of receipt, not at the trade date. */
    SUBMISSION_ANCHOR,
    /** A report whose delivery is IN_DOUBT or FILE_REJECTED counts as dead (it is live). */
    IN_DOUBT_COUNTS_AS_DEAD
  }

  private static final String DERIVED_PREFIX = "report.";
  private static final String STALE_FLAG = "SNAPSHOT_STALE";
  private static final String KEY_SEPARATOR = "|";

  private final ValidationRules rules;
  private final ReferenceData refs;
  private final ReportLedger reports;
  private final DeliveryLedger deliveries;
  private final Regime regime;
  private final Leg leg;
  private final Clock clock;
  private final Flaw flaw;

  /**
   * @param rules the rules of the pack
   * @param refs the reference data, one dataset per name that a rule gives
   * @param reports the report ledger, read by the chain check
   * @param deliveries the delivery ledger, read by the chain check
   * @param regime the regime of the report subjects the chain check looks up
   * @param leg the leg of those subjects
   * @param clock the clock of the time of receipt and of the age of the snapshots
   * @throws IllegalArgumentException a rule names a field that the canonical model does not bind,
   *     or a field of a type its check cannot read
   */
  public Validator(
      ValidationRules rules,
      ReferenceData refs,
      ReportLedger reports,
      DeliveryLedger deliveries,
      Regime regime,
      Leg leg,
      Clock clock) {
    this(rules, refs, reports, deliveries, regime, leg, clock, Flaw.NONE);
  }

  Validator(
      ValidationRules rules,
      ReferenceData refs,
      ReportLedger reports,
      DeliveryLedger deliveries,
      Regime regime,
      Leg leg,
      Clock clock,
      Flaw flaw) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.refs = Objects.requireNonNull(refs, "refs");
    this.reports = Objects.requireNonNull(reports, "reports");
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
    this.regime = Objects.requireNonNull(regime, "regime");
    this.leg = Objects.requireNonNull(leg, "leg");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.flaw = Objects.requireNonNull(flaw, "flaw");
    for (Rule rule : rules.rules()) {
      requireReadableFields(rule);
    }
  }

  private static ValueType expectedType(Check check) {
    return switch (check) {
      case DECIMAL_RANGE -> ValueType.DECIMAL;
      case TIME_NOT_AFTER_RECEIPT, TIME_NOT_OLDER -> ValueType.TIMESTAMP;
      default -> ValueType.STRING;
    };
  }

  private static void requireReadableFields(Rule rule) {
    ValueType expected = expectedType(rule.check());
    for (String field : rule.list("fields")) {
      ValueType actual =
          field.startsWith(DERIVED_PREFIX)
              ? ValueType.STRING
              : CanonicalBindings.VARIABLES.get(field);
      if (actual == null) {
        throw new IllegalArgumentException(
            "rule " + rule.id() + " names a field that the canonical model does not bind");
      }
      if (actual != expected) {
        throw new IllegalArgumentException(
            "rule " + rule.id() + " names a field of a type that its check cannot read");
      }
    }
  }

  /**
   * Validates one execution against every rule.
   *
   * @param execution the canonical execution, after enrichment
   * @param derived the derived fields of the report by name, as text
   * @throws IllegalArgumentException a dataset that a rule names is not loaded
   */
  public ValidationResult validate(CanonicalExecution execution, Map<String, String> derived) {
    Objects.requireNonNull(execution, "execution");
    Run run = new Run(execution, Map.copyOf(derived), clock.now());
    for (Rule rule : rules.rules()) {
      run.apply(rule);
    }
    return new ValidationResult(!run.failed, run.findings, new ArrayList<>(run.flags));
  }

  /** The codes of one rule: a code, and whether it is given the severity of the rule. */
  private static final class Hits {
    private final Map<String, Severity> codes = new LinkedHashMap<>();
    private final Severity ruleSeverity;

    Hits(Severity ruleSeverity) {
      this.ruleSeverity = ruleSeverity;
    }

    /** The check failed: the finding has the severity of the rule. */
    void fail(String code) {
      codes.putIfAbsent(code, ruleSeverity);
    }

    /** The check found a warning that the rule's arguments allow: the finding is a WARNING. */
    void warn(String code) {
      codes.putIfAbsent(code, Severity.WARNING);
    }
  }

  /** The state of one call. */
  private final class Run {
    private final CanonicalBindings bindings;
    private final Map<String, String> derived;
    private final Instant receipt;
    private final LocalDate tradeDate;
    private final AsOf asOf;
    private final Map<String, SnapshotInfo> snapshots = new HashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Set<String> flags = new LinkedHashSet<>();
    private final CanonicalExecution execution;
    private boolean failed;

    Run(CanonicalExecution execution, Map<String, String> derived, Instant receipt) {
      this.execution = execution;
      this.bindings = new CanonicalBindings(execution);
      this.derived = derived;
      this.receipt = receipt;
      this.tradeDate = execution.tradedAt().atZone(ZoneOffset.UTC).toLocalDate();
      this.asOf = new AsOf(tradeDate, receipt.atZone(ZoneOffset.UTC).toLocalDate());
    }

    void apply(Rule rule) {
      Hits hits = new Hits(rule.severity());
      switch (rule.check()) {
        case DECIMAL_RANGE -> decimalRange(rule, hits);
        case LEI ->
            texts(
                rule,
                text -> {
                  if (!Lei.hasShape(text)) {
                    hits.fail("LEI_SHAPE");
                  } else if (!Lei.isValid(text)) {
                    hits.fail("LEI_CHECK_DIGITS");
                  }
                });
        case ISIN ->
            texts(
                rule,
                text -> {
                  if (!Isin.hasShape(text)) {
                    hits.fail("ISIN_SHAPE");
                  } else if (!Isin.isValid(text)) {
                    hits.fail("ISIN_CHECK_DIGIT");
                  }
                });
        case MIC_SHAPE ->
            texts(
                rule,
                text -> {
                  if (!Mic.hasShape(text)) {
                    hits.fail("MIC_SHAPE");
                  }
                });
        case CURRENCY_SHAPE ->
            texts(
                rule,
                text -> {
                  if (!Currency.hasShape(text)) {
                    hits.fail("CURRENCY_SHAPE");
                  }
                });
        case CURRENCY_NOT_IN ->
            texts(
                rule,
                text -> {
                  if (rule.list("values").contains(text)) {
                    hits.fail("CURRENCY_EXCLUDED");
                  }
                });
        case REF_STATUS -> refStatus(rule, hits);
        case REF_LISTED -> refListed(rule, hits);
        case REF_KNOWN -> refKnown(rule, hits);
        case TIME_NOT_AFTER_RECEIPT ->
            instants(
                rule,
                instant -> {
                  if (instant.isAfter(receipt)) {
                    hits.fail("TIME_AFTER_RECEIPT");
                  }
                });
        case TIME_NOT_OLDER -> timeNotOlder(rule, hits);
        case CHAIN_FIT -> chainFit(rule, hits);
      }
      record(rule, hits);
    }

    private void record(Rule rule, Hits hits) {
      for (Map.Entry<String, Severity> hit : hits.codes.entrySet()) {
        Severity severity = hit.getValue();
        findings.add(new Finding(rule.id(), rule.layer(), severity, hit.getKey()));
        if (severity == Severity.WARNING) {
          flags.add(hit.getKey());
        }
        boolean blocks =
            severity == Severity.BLOCKER && rule.disposition() == Disposition.QUARANTINE;
        if ((blocks && flaw != Flaw.BLOCKER_PASSES)
            || (severity == Severity.WARNING && flaw == Flaw.WARNING_FAILS_RECORD)) {
          failed = true;
        }
      }
    }

    // -- reading fields ---------------------------------------------------------------------

    private Optional<Value> value(String field) {
      if (field.startsWith(DERIVED_PREFIX)) {
        String text = derived.get(field.substring(DERIVED_PREFIX.length()));
        return text == null ? Optional.empty() : Optional.of(new Value.StringValue(text));
      }
      return bindings.get(field);
    }

    private void texts(Rule rule, java.util.function.Consumer<String> check) {
      for (String field : rule.list("fields")) {
        value(field).ifPresent(v -> check.accept(((Value.StringValue) v).value()));
      }
    }

    private void instants(Rule rule, java.util.function.Consumer<Instant> check) {
      for (String field : rule.list("fields")) {
        value(field).ifPresent(v -> check.accept(((Value.TimestampValue) v).value()));
      }
    }

    // -- the checks -------------------------------------------------------------------------

    private void decimalRange(Rule rule, Hits hits) {
      Optional<BigDecimal> min = rule.single("min_exclusive").map(BigDecimal::new);
      Optional<Integer> maxInteger = rule.single("max_integer_digits").map(Integer::valueOf);
      Optional<Integer> maxFraction = rule.single("max_fraction_digits").map(Integer::valueOf);
      for (String field : rule.list("fields")) {
        Optional<Value> v = value(field);
        if (v.isEmpty()) {
          continue;
        }
        BigDecimal number = ((Value.DecimalValue) v.get()).value();
        if (min.isPresent() && number.compareTo(min.get()) <= 0) {
          hits.fail("DECIMAL_NOT_ABOVE_MIN");
        }
        // zeros at the end of the fraction are not digits of the value
        BigDecimal plain = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        int fraction = Math.max(0, plain.scale());
        // in long: precision minus a very negative scale does not fit an int
        long integer =
            plain.signum() == 0 ? 0L : Math.max(0L, (long) plain.precision() - plain.scale());
        if (maxInteger.isPresent() && integer > maxInteger.get()) {
          hits.fail("DECIMAL_INTEGER_DIGITS");
        }
        if (maxFraction.isPresent() && fraction > maxFraction.get()) {
          hits.fail("DECIMAL_FRACTION_DIGITS");
        }
      }
    }

    private SnapshotInfo snapshot(DatasetId dataset) {
      SnapshotInfo cached = snapshots.get(dataset.name());
      if (cached != null) {
        return cached;
      }
      SnapshotInfo info;
      try {
        info = refs.currentSnapshot(dataset);
      } catch (IllegalArgumentException e) {
        // the text of the store names more than the dataset: it is not passed on
        throw new IllegalArgumentException(
            "reference dataset " + dataset.name() + " is not loaded");
      }
      snapshots.put(dataset.name(), info);
      if (flaw != Flaw.NO_STALE_FLAG && receipt.isAfter(info.loadedAt().plus(info.maxAge()))) {
        flags.add(STALE_FLAG);
      }
      return info;
    }

    private Optional<Entry> lookup(DatasetId dataset, SnapshotInfo snapshot, String key) {
      Anchor anchor = flaw == Flaw.SUBMISSION_ANCHOR ? Anchor.SUBMISSION_DATE : Anchor.TRADE_DATE;
      return refs.lookup(dataset, snapshot.id(), key, asOf, anchor);
    }

    private void refStatus(Rule rule, Hits hits) {
      DatasetId dataset = new DatasetId(rule.single("dataset").orElseThrow());
      SnapshotInfo snapshot = snapshot(dataset);
      String attribute = rule.single("attribute").orElseThrow();
      List<String> allowed = rule.list("allowed");
      List<String> warnValues = rule.list("warn_values");
      texts(
          rule,
          key -> {
            Optional<Entry> entry = lookup(dataset, snapshot, key);
            if (entry.isEmpty()) {
              hits.fail("REF_NOT_FOUND");
              return;
            }
            String status = entry.get().attributes().get(attribute);
            if (status != null && allowed.contains(status)) {
              return;
            }
            if (status != null && warnValues.contains(status)) {
              hits.warn(rule.single("warn_code").orElseThrow());
            } else {
              hits.fail("REF_STATUS_NOT_ALLOWED");
            }
          });
    }

    private void refListed(Rule rule, Hits hits) {
      DatasetId dataset = new DatasetId(rule.single("dataset").orElseThrow());
      SnapshotInfo snapshot = snapshot(dataset);
      List<String> parts = new ArrayList<>();
      texts(rule, parts::add);
      if (parts.size() != rule.list("fields").size()) {
        return; // a field is absent: there is no key to list
      }
      if (lookup(dataset, snapshot, String.join(KEY_SEPARATOR, parts)).isEmpty()) {
        LocalDate snapshotDate = snapshot.loadedAt().atZone(ZoneOffset.UTC).toLocalDate();
        // an unlisted instrument on a trade of the snapshot day or later may simply be new
        hits.fail(tradeDate.isBefore(snapshotDate) ? "REFDATA_UNKNOWN" : "REFDATA_LAG_EXPECTED");
      }
    }

    private void refKnown(Rule rule, Hits hits) {
      DatasetId dataset = new DatasetId(rule.single("dataset").orElseThrow());
      SnapshotInfo snapshot = snapshot(dataset);
      texts(
          rule,
          key -> {
            if (lookup(dataset, snapshot, key).isEmpty()) {
              hits.fail("REF_NOT_FOUND");
            }
          });
    }

    private void timeNotOlder(Rule rule, Hits hits) {
      int years = Integer.parseInt(rule.single("years").orElseThrow());
      Instant limit = receipt.atZone(ZoneOffset.UTC).minusYears(years).toInstant();
      instants(
          rule,
          instant -> {
            boolean tooOld =
                flaw == Flaw.AGE_LIMIT_EXCLUSIVE
                    ? !instant.isAfter(limit)
                    : instant.isBefore(limit);
            if (tooOld) {
              hits.fail("TIME_TOO_OLD");
            }
          });
    }

    private void chainFit(Rule rule, Hits hits) {
      LifecycleKind kind = execution.eventKind();
      if (kind != LifecycleKind.NEW && kind != LifecycleKind.CANCEL) {
        return;
      }
      Optional<String> latest = latestLiveAction();
      if (kind == LifecycleKind.CANCEL) {
        if (latest.isEmpty() || !latest.get().equals(rule.single("new_action").orElseThrow())) {
          hits.fail("CANCEL_WITHOUT_LIVE_NEWT");
        }
      } else if (latest.isPresent()
          && !latest.get().equals(rule.single("cancel_action").orElseThrow())) {
        hits.fail("NEWT_OVER_LIVE_NEWT");
      }
    }

    /** The action of the latest live version of the subject, if it has one. */
    private Optional<String> latestLiveAction() {
      SubjectKey key = new SubjectKey(regime, execution.eventIdentity(), leg);
      Optional<ReportLedger.Subject> subject = reports.subject(key);
      if (subject.isEmpty()) {
        return Optional.empty();
      }
      List<StoredVersion> versions = reports.versions(subject.get().id());
      for (int i = versions.size() - 1; i >= 0; i--) {
        StoredVersion version = versions.get(i);
        if (live(version)) {
          return Optional.of(version.action());
        }
      }
      return Optional.empty();
    }

    /** A version is live unless its delivery is rejected, in repair, withdrawn or written off. */
    private boolean live(StoredVersion version) {
      if (flaw == Flaw.DEAD_COUNTS_AS_LIVE || version.delivery().isEmpty()) {
        return true;
      }
      return deliveries
          .delivery(version.delivery().get())
          .map(d -> !isDead(d.state()))
          .orElse(true);
    }
  }

  /**
   * Dead for V15: the delivery is rejected, in repair, withdrawn or written off. Every other state
   * is live, SUPERSEDED, IN_DOUBT and FILE_REJECTED included: an unresolved delivery of the key
   * keeps its report in the chain.
   */
  private boolean isDead(DeliveryState state) {
    if (flaw == Flaw.IN_DOUBT_COUNTS_AS_DEAD
        && (state == DeliveryState.IN_DOUBT || state == DeliveryState.FILE_REJECTED)) {
      return true;
    }
    return state == DeliveryState.REJECTED
        || state == DeliveryState.IN_REPAIR
        || state == DeliveryState.WITHDRAWN
        || state == DeliveryState.WRITTEN_OFF;
  }
}
