package dev.regpack.spikes.x.bench;

import dev.regpack.bench.data.TradeRecordGen;
import dev.regpack.bench.proto.Workload;
import dev.regpack.spikes.x.xml.StaxRecordWriter;
import dev.regpack.spikes.x.xml.TxModel;
import dev.regpack.spikes.x.xml.XmlRecordGen;
import java.io.OutputStream;

/**
 * The StAX writer on its own: it writes a ring of pre-generated records, over and over, into one
 * document whose sink discards the bytes. One unit is one record. The generation of the ring is
 * outside the measurement. The checksum folds the number of bytes written after every call, so the
 * JIT cannot discard the work.
 *
 * <p>Not thread-safe: one instance per thread.
 */
public final class WriterBench implements Workload {
  /** The header values of the one document that the bench writes. */
  public static final String MESSAGE_ID = "writer-bench";

  public static final long CREATION_MICROS = 0L;

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final TxModel[] ring;
  private final StaxRecordWriter writer;
  private int position;
  private long checksum;

  /** A ring of {@code ringSize} records of the seed. */
  public WriterBench(long seed, int ringSize) {
    if (ringSize <= 0) {
      throw new IllegalArgumentException("ringSize must be positive: " + ringSize);
    }
    TradeRecordGen trades = new TradeRecordGen(seed);
    XmlRecordGen gen = new XmlRecordGen(seed);
    this.ring = new TxModel[ringSize];
    for (int i = 0; i < ring.length; i++) {
      ring[i] = gen.next(trades.next());
    }
    this.writer = new StaxRecordWriter(OutputStream.nullOutputStream());
    writer.startDocument(MESSAGE_ID, CREATION_MICROS);
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    for (long i = 0; i < maxUnits; i++) {
      writer.writeTx(ring[position]);
      position = position + 1 == ring.length ? 0 : position + 1;
    }
    checksum = (checksum ^ writer.bytesWritten()) * GOLDEN + maxUnits;
    return maxUnits;
  }

  @Override
  public long checksum() {
    return checksum;
  }

  /** The bytes written so far, the header of the document included. */
  public long bytesWritten() {
    return writer.bytesWritten();
  }
}
