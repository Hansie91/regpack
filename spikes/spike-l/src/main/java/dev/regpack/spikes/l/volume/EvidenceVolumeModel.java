package dev.regpack.spikes.l.volume;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evidence volume of a day at the journal granularity of spec 11.2: raw-input and
 * enrichment-response bytes, objects per day and PUT/s (spec 5.6). A journal object records a fact
 * at delivery, attempt or micro-batch level, never one per record: one {@code BatchAdmitted} and
 * one batched {@code VersionAssigned} per micro-batch, {@code DeliveryIntent} and {@code
 * TransportResult} per attempt (a file), {@code FeedbackReceived} per feedback file, and {@code
 * RecoveryDecision} and {@code OperatorAction} as rates.
 *
 * <p>Stated assumptions, repeated in {@link Volume#notes()}: every record is one report (the upper
 * bound of the files per day), the day's objects are all written inside the burst window, and each
 * attempt is answered by one feedback file.
 */
public final class EvidenceVolumeModel {
  private static final int SCALE = 3;
  private static final int RATE_SCALE = 9;
  private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);
  private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600L);

  /** The seven journal types of release 1, in the order of spec 11.2. */
  public static final List<String> JOURNAL_TYPES =
      List.of(
          "BatchAdmitted",
          "VersionAssigned",
          "DeliveryIntent",
          "TransportResult",
          "FeedbackReceived",
          "RecoveryDecision",
          "OperatorAction");

  private EvidenceVolumeModel() {}

  /**
   * The inputs of the model.
   *
   * @param recordsPerDay candidate records per day (50,000,000)
   * @param microBatchN records per micro-batch (N)
   * @param burstHours the hours in which the day's records are processed (the burst window)
   * @param rawBytesPerRecord bytes of one raw landed record
   * @param maxReportsPerFile reports per sealed file (the transport's file limit)
   * @param resendRate the share of files that are sent again (an extra attempt each)
   * @param enrichmentPhasesPerBatch enrichment phases per micro-batch, one response object each
   * @param enrichmentBytesPerBatch bytes of the enrichment responses of one micro-batch (all
   *     phases)
   */
  public record Inputs(
      long recordsPerDay,
      int microBatchN,
      BigDecimal burstHours,
      long rawBytesPerRecord,
      int maxReportsPerFile,
      BigDecimal resendRate,
      int enrichmentPhasesPerBatch,
      long enrichmentBytesPerBatch) {
    public Inputs {
      if (recordsPerDay <= 0) {
        throw new IllegalArgumentException("recordsPerDay must be positive: " + recordsPerDay);
      }
      if (microBatchN <= 0) {
        throw new IllegalArgumentException("microBatchN must be positive: " + microBatchN);
      }
      if (burstHours.signum() <= 0 || burstHours.compareTo(BigDecimal.valueOf(24)) > 0) {
        throw new IllegalArgumentException("burstHours must be in (0, 24]: " + burstHours);
      }
      if (rawBytesPerRecord < 0 || enrichmentBytesPerBatch < 0) {
        throw new IllegalArgumentException("byte counts must not be negative");
      }
      if (maxReportsPerFile <= 0) {
        throw new IllegalArgumentException(
            "maxReportsPerFile must be positive: " + maxReportsPerFile);
      }
      if (resendRate.signum() < 0) {
        throw new IllegalArgumentException("resendRate must not be negative: " + resendRate);
      }
      if (enrichmentPhasesPerBatch < 0) {
        throw new IllegalArgumentException("enrichmentPhasesPerBatch must not be negative");
      }
    }
  }

  /**
   * The volume of a day.
   *
   * @param landingObjectsPerDay landed batches (one content-addressed object each)
   * @param journalObjectsPerDay objects per day by journal type, in the order of {@link
   *     #JOURNAL_TYPES}
   * @param sealedFilesPerDay sealed report files (evidence objects)
   * @param enrichmentObjectsPerDay enrichment-response objects
   * @param rawBytesPerDay the landed raw bytes
   * @param enrichmentBytesPerDay the enrichment-response bytes
   * @param objectsPerDay every evidence object of a day
   * @param burstRecordsPerSecond records per second inside the burst window
   * @param averagePutPerSecond PUTs per second if the day's objects were spread over 24 hours
   * @param burstPutPerSecond PUTs per second inside the burst window
   * @param notes the assumptions behind the numbers
   */
  public record Volume(
      long landingObjectsPerDay,
      Map<String, BigDecimal> journalObjectsPerDay,
      long sealedFilesPerDay,
      long enrichmentObjectsPerDay,
      long rawBytesPerDay,
      long enrichmentBytesPerDay,
      BigDecimal objectsPerDay,
      BigDecimal burstRecordsPerSecond,
      BigDecimal averagePutPerSecond,
      BigDecimal burstPutPerSecond,
      List<String> notes) {
    public Volume {
      journalObjectsPerDay =
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(journalObjectsPerDay));
      notes = List.copyOf(notes);
    }
  }

  /** Computes the volume of a day. */
  public static Volume compute(Inputs in) {
    long batches = ceilDiv(in.recordsPerDay(), in.microBatchN());
    long files = ceilDiv(in.recordsPerDay(), in.maxReportsPerFile());
    BigDecimal resends = BigDecimal.valueOf(files).multiply(in.resendRate());
    BigDecimal attempts = BigDecimal.valueOf(files).add(resends);

    Map<String, BigDecimal> journal = new LinkedHashMap<>();
    journal.put("BatchAdmitted", count(batches));
    journal.put("VersionAssigned", count(batches));
    journal.put("DeliveryIntent", attempts.setScale(SCALE, RoundingMode.HALF_EVEN));
    journal.put("TransportResult", attempts.setScale(SCALE, RoundingMode.HALF_EVEN));
    journal.put("FeedbackReceived", attempts.setScale(SCALE, RoundingMode.HALF_EVEN));
    journal.put("RecoveryDecision", resends.setScale(SCALE, RoundingMode.HALF_EVEN));
    journal.put("OperatorAction", BigDecimal.ZERO.setScale(SCALE));

    long enrichmentObjects = Math.multiplyExact(batches, (long) in.enrichmentPhasesPerBatch());
    long rawBytes = Math.multiplyExact(in.recordsPerDay(), in.rawBytesPerRecord());
    long enrichmentBytes = Math.multiplyExact(batches, in.enrichmentBytesPerBatch());

    BigDecimal objects = count(batches).add(count(files)).add(count(enrichmentObjects));
    for (BigDecimal value : journal.values()) {
      objects = objects.add(value);
    }
    BigDecimal burstSeconds = in.burstHours().multiply(SECONDS_PER_HOUR);
    List<String> notes =
        List.of(
            "every record is one report, so the files per day are records over reports per file:"
                + " the upper bound",
            "all objects of the day are written inside the burst window of "
                + in.burstHours().toPlainString()
                + " h",
            "each attempt is answered by one feedback file",
            "OperatorAction is operator-driven (approvals, write-offs, pack activations: a handful"
                + " a day, spec 11.2) and is counted as 0; RecoveryDecision is one per resent file",
            "TransportResult is per attempt (file) and VersionAssigned is one batched object per"
                + " micro-batch, never per record");
    return new Volume(
        batches,
        journal,
        files,
        enrichmentObjects,
        rawBytes,
        enrichmentBytes,
        objects,
        BigDecimal.valueOf(in.recordsPerDay())
            .divide(burstSeconds, RATE_SCALE, RoundingMode.HALF_EVEN),
        objects.divide(SECONDS_PER_DAY, RATE_SCALE, RoundingMode.HALF_EVEN),
        objects.divide(burstSeconds, RATE_SCALE, RoundingMode.HALF_EVEN),
        notes);
  }

  private static BigDecimal count(long value) {
    return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.UNNECESSARY);
  }

  private static long ceilDiv(long a, long b) {
    return Math.floorDiv(a + b - 1, b);
  }
}
