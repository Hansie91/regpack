package dev.regpack.spikes.x.bench;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.x.validate.DefectInjector;
import dev.regpack.spikes.x.validate.PerRecordValidator;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.util.List;
import java.util.Objects;
import javax.xml.validation.Schema;

/**
 * The per-record XSD gate on its own: it validates a ring of pre-wrapped records, over and over,
 * with one reused validator. One unit is one record. Generating and wrapping the ring is outside
 * the measurement. A record that is not valid is a defect of the benchmark and fails it, so the
 * rate is the rate of validating valid records. The checksum folds the length of every record.
 *
 * <p>Not thread-safe: one instance per thread (the schema may be shared).
 */
public final class RecordXsdBench implements Workload {
  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final PerRecordValidator validator;
  private final List<byte[]> ring;
  private int position;
  private long checksum;

  /** A ring of {@code ringSize} generated records of the seed. */
  public RecordXsdBench(Schema schema, long seed, int ringSize) {
    this(schema, generate(schema, seed, ringSize));
  }

  /** A ring of records that the caller wrapped with {@link PerRecordValidator#wrap}. */
  public RecordXsdBench(Schema schema, List<byte[]> wrappedRing) {
    Objects.requireNonNull(schema, "schema");
    if (wrappedRing.isEmpty()) {
      throw new IllegalArgumentException("the ring must not be empty");
    }
    this.validator = new PerRecordValidator(schema);
    this.ring = List.copyOf(wrappedRing);
  }

  private static List<byte[]> generate(Schema schema, long seed, int ringSize) {
    if (ringSize <= 0) {
      throw new IllegalArgumentException("ringSize must be positive: " + ringSize);
    }
    PerRecordValidator wrapper = new PerRecordValidator(schema);
    TradeRecordGen trades = new TradeRecordGen(seed);
    XmlRecordGen gen = new XmlRecordGen(seed);
    byte[][] ring = new byte[ringSize][];
    for (int i = 0; i < ringSize; i++) {
      ring[i] = wrapper.wrap(DefectInjector.serialise(gen.next(trades.next())));
    }
    return List.of(ring);
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    long sum = checksum;
    for (long i = 0; i < maxUnits; i++) {
      byte[] record = ring.get(position);
      int errors = validator.validate(record);
      if (errors != 0) {
        throw new IllegalStateException(
            "record " + position + " of the ring is not valid: " + validator.firstError());
      }
      sum = (sum ^ record.length) * GOLDEN + position;
      position = position + 1 == ring.size() ? 0 : position + 1;
    }
    checksum = sum;
    return maxUnits;
  }

  @Override
  public long checksum() {
    return checksum;
  }
}
