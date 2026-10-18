package dev.regpack.spikes.l.load;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import java.util.ArrayList;
import java.util.List;

/**
 * The unit of load of SP-L: a micro-batch of candidates (spec 7.8), each of which is ingested,
 * decided and either reported (REPORT, with one to a few legs) or not. A REPORT candidate that
 * supersedes an earlier report (an amend or a cancel) reuses that report's event identity.
 *
 * <p>Keys are hashes: the event identity and the source record id of a candidate are 16-character
 * lower-case hex strings of a SplitMix64 mix of (seed, sequence), so the key order is independent
 * of the arrival order and inserts into the unique indexes land at random places in the B-tree
 * instead of appending at the right edge (a cached right-edge insert would hide the cost of an
 * index larger than memory). The generator's own {@code executionId} is not a key.
 *
 * <p>The model is stateless; the caller owns {@code existing}, the identities that may still be
 * superseded. Only identities whose report is committed belong there, so that a superseding report
 * never refers to a subject that does not exist yet. A superseded identity is removed: it is
 * superseded once.
 */
public final class LedgerLoadModel {
  /** Draws are taken in parts per million. */
  private static final long PPM = 1_000_000L;

  private static final long IDENTITY_SALT = 0x1D1D1D1D1D1D1D1DL;
  private static final long RECORD_SALT = 0x5EC0DE5EC0DE5EC0L;
  private static final long LEG_SALT = 0x1E65_1E65_1E65_1E65L;
  private static final long PATH_SALT = 0x9A7B_9A7B_9A7B_9A7BL;
  private static final int MAX_LEGS = 7;

  /**
   * The parameters of a batch.
   *
   * @param batchSize candidates per micro-batch (N)
   * @param reportShare share of candidates whose decision is REPORT, superseding ones included
   * @param legsPerReport mean legs of a REPORT candidate, 1 to 7; legs are a function of the event
   *     identity, so a superseding report has the legs of the report it supersedes
   * @param amendRatio share of candidates that supersede an earlier report (amend and cancel)
   * @param seed the seed of the identity hashes
   */
  public record Params(
      int batchSize, double reportShare, double legsPerReport, double amendRatio, long seed) {
    public Params {
      if (batchSize <= 0) {
        throw new IllegalArgumentException("batchSize must be positive: " + batchSize);
      }
      if (!(reportShare >= 0.0 && reportShare <= 1.0)) {
        throw new IllegalArgumentException("reportShare must be in [0, 1]: " + reportShare);
      }
      if (!(legsPerReport >= 1.0 && legsPerReport <= MAX_LEGS)) {
        throw new IllegalArgumentException("legsPerReport must be in [1, 7]: " + legsPerReport);
      }
      if (!(amendRatio >= 0.0 && amendRatio <= reportShare)) {
        throw new IllegalArgumentException(
            "amendRatio must be in [0, reportShare]: " + amendRatio + " > " + reportShare);
      }
    }
  }

  /**
   * One candidate of a batch.
   *
   * @param record the synthetic trade event
   * @param eventIdentity the event identity (16 hex characters): new, or that of the superseded
   *     report
   * @param sourceRecordId the source record id of the ingest row (16 hex characters, new)
   * @param report whether the decision is REPORT
   * @param legs legs of the report, 0 when it is not reported
   * @param superseding whether it supersedes an earlier report (implies {@code report})
   */
  public record Candidate(
      TradeRecord record,
      String eventIdentity,
      String sourceRecordId,
      boolean report,
      int legs,
      boolean superseding) {}

  /**
   * The evaluated steps of one decision. {@code ruleOrdinals}, {@code matched} and {@code
   * costUnits} are the rows of the deciding table (encoding E1); {@link #verboseJson()} adds the
   * rows of the tables evaluated before it (encoding E3, every evaluated step).
   */
  public record SyntheticPath(
      long seed, long seq, int[] ruleOrdinals, boolean[] matched, int[] costUnits) {
    /** The verbose path of E3: a JSON array with one object per evaluated step of every table. */
    public String verboseJson() {
      SplitMix64 rng = new SplitMix64(seed ^ PATH_SALT ^ (seq * 31L));
      StringBuilder out = new StringBuilder(ruleOrdinals.length * 130);
      out.append('[');
      boolean first = true;
      for (int table = 0; table < 2; table++) {
        int steps = 1 + rng.nextInt(ruleOrdinals.length);
        int ordinal = rng.nextInt(5);
        for (int i = 0; i < steps; i++) {
          ordinal += 1 + rng.nextInt(4);
          first = step(out, first, table, ordinal, rng.chance(1, 4), 1 + rng.nextInt(30));
        }
      }
      for (int i = 0; i < ruleOrdinals.length; i++) {
        first = step(out, first, 2, ruleOrdinals[i], matched[i], costUnits[i]);
      }
      return out.append(']').toString();
    }

    private static boolean step(
        StringBuilder out, boolean first, int table, int rule, boolean matched, int cost) {
      if (!first) {
        out.append(',');
      }
      out.append("{\"table\":")
          .append(table)
          .append(",\"rule\":")
          .append(rule)
          .append(",\"matched\":")
          .append(matched)
          .append(",\"cost\":")
          .append(cost)
          .append('}');
      return false;
    }
  }

  private LedgerLoadModel() {}

  /**
   * The next batch: {@code params.batchSize()} candidates from {@code gen}. Draws of {@code rng}
   * decide REPORT and superseding; a candidate that is chosen to supersede while {@code existing}
   * is empty is an ordinary candidate. Superseded identities are removed from {@code existing}
   * (swap-remove, so the order of the list is not kept).
   */
  public static List<Candidate> nextBatch(
      Params params, TradeRecordGen gen, SplitMix64 rng, List<String> existing) {
    long amendPpm = Math.round(params.amendRatio() * PPM);
    double notAmend = 1.0 - params.amendRatio();
    double conditional =
        notAmend <= 0.0 ? 1.0 : (params.reportShare() - params.amendRatio()) / notAmend;
    long reportPpm = Math.round(conditional * PPM);
    List<Candidate> out = new ArrayList<>(params.batchSize());
    for (int i = 0; i < params.batchSize(); i++) {
      TradeRecord record = gen.next();
      boolean amendDraw = rng.nextLong(PPM) < amendPpm;
      boolean reportDraw = rng.nextLong(PPM) < reportPpm;
      String identity;
      boolean report;
      boolean superseding = false;
      if (amendDraw && !existing.isEmpty()) {
        int pick = rng.nextInt(existing.size());
        identity = existing.get(pick);
        int last = existing.size() - 1;
        existing.set(pick, existing.get(last));
        existing.remove(last);
        superseding = true;
        report = true;
      } else {
        identity = identityOf(params.seed(), record.seq());
        report = reportDraw;
      }
      out.add(
          new Candidate(
              record,
              identity,
              sourceRecordIdOf(params.seed(), record.seq()),
              report,
              report ? legsOf(params, identity) : 0,
              superseding));
    }
    return out;
  }

  /** The event identity of the candidate with this sequence number: 16 lower-case hex digits. */
  public static String identityOf(long seed, long sequence) {
    return hex16(new SplitMix64((seed ^ IDENTITY_SALT) + sequence).nextLong());
  }

  /** The ingest source record id of the candidate with this sequence number. */
  public static String sourceRecordIdOf(long seed, long sequence) {
    return hex16(new SplitMix64((seed ^ RECORD_SALT) + sequence).nextLong());
  }

  /**
   * The legs of the report of an event identity: a function of the identity, so every version of a
   * report has the same legs. The mean over identities is {@code params.legsPerReport()}.
   */
  public static int legsOf(Params params, String eventIdentity) {
    long id = Long.parseUnsignedLong(eventIdentity, 16);
    double unit = (new SplitMix64(id ^ LEG_SALT).nextLong() >>> 11) * 0x1.0p-53;
    int base = (int) Math.floor(params.legsPerReport());
    double fraction = params.legsPerReport() - base;
    return base + (unit < fraction ? 1 : 0);
  }

  /** The synthetic decision path of the candidate with this sequence number. */
  public static SyntheticPath syntheticPath(long seed, long sequence) {
    SplitMix64 rng = new SplitMix64((seed ^ PATH_SALT) + sequence);
    int steps = 3 + rng.nextInt(38);
    int[] ordinals = new int[steps];
    boolean[] matched = new boolean[steps];
    int[] costs = new int[steps];
    int ordinal = rng.nextInt(5);
    for (int i = 0; i < steps; i++) {
      ordinal += 1 + rng.nextInt(4);
      ordinals[i] = ordinal;
      matched[i] = rng.chance(1, 4);
      costs[i] = 1 + rng.nextInt(30);
    }
    return new SyntheticPath(seed, sequence, ordinals, matched, costs);
  }

  /** The digits of a value, left-padded with zeros to 16 lower-case hex digits. */
  static String hex16(long value) {
    String digits = Long.toHexString(value);
    if (digits.length() == 16) {
      return digits;
    }
    return "0".repeat(16 - digits.length()) + digits;
  }
}
