package dev.regpack.spikes.x.bench;

import dev.regpack.bench.data.SplitMix64;
import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.x.validate.Bisect;
import dev.regpack.spikes.x.validate.DefectInjector;
import dev.regpack.spikes.x.validate.DefectInjector.Kind;
import dev.regpack.spikes.x.validate.PerRecordValidator;
import dev.regpack.spikes.x.xml.TxModel;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;

/**
 * The cost of finding one defective record in a file by halving, with the real schema: one cycle is
 * one {@link Bisect#findOffenders} over {@code records} records of which one is defective, each
 * subset validated as a whole file by a streaming validator. One cycle is one unit, so the rate is
 * bisects per second and the reciprocal is the seconds per defect (the bisect cost of the policy
 * model, rebuild excluded). The generation and serialisation of the records are outside the
 * measurement. A bisect that does not find exactly the defective record fails the benchmark.
 *
 * <p>{@link #validationCount} is the same search with a counting predicate instead of a validator:
 * how many validations a bisect needs, which does not depend on the machine.
 *
 * <p>Not thread-safe: one instance per thread.
 */
public final class BisectBench implements Workload {
  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final Schema schema;
  private final List<byte[]> records;
  private final int offender;
  private final byte[] prefix;
  private final byte[] suffix;
  private long validations;
  private long bytes;
  private long checksum;

  /** {@code records} generated records of the seed, one of them defective at a seeded position. */
  public BisectBench(Schema schema, int records, long seed) {
    this.schema = Objects.requireNonNull(schema, "schema");
    if (records < 1) {
      throw new IllegalArgumentException("records must be at least 1: " + records);
    }
    SplitMix64 rng = new SplitMix64(seed ^ 0x6A09E667F3BCC908L);
    this.offender = rng.nextInt(records);
    TradeRecordGen trades = new TradeRecordGen(seed);
    XmlRecordGen gen = new XmlRecordGen(seed);
    List<byte[]> list = new ArrayList<>(records);
    for (int i = 0; i < records; i++) {
      TxModel model = gen.next(trades.next());
      list.add(
          DefectInjector.serialise(
              i == offender ? DefectInjector.corrupt(model, Kind.PATTERN, i) : model));
    }
    this.records = list;
    // the file around a set of records: the wrapper of the per-record validator, split in two
    byte[] wrapped = new PerRecordValidator(schema).wrap(new byte[0]);
    String text = new String(wrapped, StandardCharsets.UTF_8);
    int split = text.indexOf("</transactions>");
    if (split < 0) {
      throw new IllegalStateException("the record wrapper has no transactions element");
    }
    this.prefix = text.substring(0, split).getBytes(StandardCharsets.UTF_8);
    this.suffix = text.substring(split).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public long cycleUnits() {
    return 1;
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    for (long i = 0; i < maxUnits; i++) {
      Bisect.Result result = Bisect.findOffenders(records, this::validSubset);
      if (!result.offenders().equals(List.of(offender))) {
        throw new IllegalStateException(
            "the bisect found " + result.offenders() + ", the defective record is " + offender);
      }
      validations = result.validations();
      bytes = result.bytesValidated();
      checksum = (checksum ^ result.validations() ^ result.bytesValidated()) * GOLDEN + offender;
    }
    return maxUnits;
  }

  private boolean validSubset(List<byte[]> subset) {
    Validator validator = FileGateBench.newValidator(schema);
    try {
      validator.validate(new StreamSource(fileOf(subset)));
      return true;
    } catch (SAXException e) {
      // only a schema violation says "this subset has an offender"; a file that is not well
      // formed, or a parser limit, is a defect of the benchmark and must not look like one
      if (String.valueOf(e.getMessage()).startsWith("cvc-")) {
        return false;
      }
      throw new IllegalStateException("a subset could not be validated: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The bytes of the file around the subset, streamed without being copied. */
  private InputStream fileOf(List<byte[]> subset) {
    Iterator<byte[]> parts = subset.iterator();
    return new SequenceInputStream(
        new Enumeration<InputStream>() {
          // 0: the prefix is next, 1: the records, 2: everything has been handed out
          private int stage;

          @Override
          public boolean hasMoreElements() {
            return stage < 2;
          }

          @Override
          public InputStream nextElement() {
            if (stage == 0) {
              stage = 1;
              return new ByteArrayInputStream(prefix);
            }
            if (parts.hasNext()) {
              return new ByteArrayInputStream(parts.next());
            }
            stage = 2;
            return new ByteArrayInputStream(suffix);
          }
        });
  }

  /** Validations of the last bisect. */
  public long lastValidations() {
    return validations;
  }

  /** Bytes validated by the last bisect, over all its validations. */
  public long lastBytesValidated() {
    return bytes;
  }

  /** The index of the defective record. */
  public int offenderIndex() {
    return offender;
  }

  @Override
  public long checksum() {
    return checksum;
  }

  /**
   * How many validations a bisect of {@code records} records with {@code offenders} defective ones
   * (at seeded positions) needs, with a predicate that only looks for the defective records; no
   * schema is involved. Returns the search result: the offenders found and the validations.
   */
  public static Bisect.Result validationCount(int records, int offenders, long seed) {
    if (records < 1 || offenders < 0 || offenders > records) {
      throw new IllegalArgumentException("records " + records + ", offenders " + offenders);
    }
    List<byte[]> list = new ArrayList<>(records);
    for (int i = 0; i < records; i++) {
      list.add(new byte[] {(byte) i});
    }
    Set<byte[]> marked = Collections.newSetFromMap(new IdentityHashMap<>());
    SplitMix64 rng = new SplitMix64(seed);
    while (marked.size() < offenders) {
      marked.add(list.get(rng.nextInt(records)));
    }
    return Bisect.findOffenders(
        list,
        subset -> {
          for (byte[] record : subset) {
            if (marked.contains(record)) {
              return false;
            }
          }
          return true;
        });
  }
}
