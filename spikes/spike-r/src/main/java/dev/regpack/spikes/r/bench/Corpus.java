package dev.regpack.spikes.r.bench;

import dev.regpack.bench.data.TradeRecord;
import dev.regpack.bench.data.TradeRecordGen;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A corpus of synthetic records that is generated while it is read: a million records are never
 * held in memory. Every iterator starts again from the seed, so the corpus can be read twice.
 */
public final class Corpus implements Iterable<TradeRecord> {
  private final long seed;
  private final long size;

  public Corpus(long seed, long size) {
    if (size < 0) {
      throw new IllegalArgumentException("size must not be negative: " + size);
    }
    this.seed = seed;
    this.size = size;
  }

  @Override
  public Iterator<TradeRecord> iterator() {
    TradeRecordGen generator = new TradeRecordGen(seed);
    return new Iterator<>() {
      private long produced;

      @Override
      public boolean hasNext() {
        return produced < size;
      }

      @Override
      public TradeRecord next() {
        if (produced >= size) {
          throw new NoSuchElementException();
        }
        produced++;
        return generator.next();
      }
    };
  }
}
