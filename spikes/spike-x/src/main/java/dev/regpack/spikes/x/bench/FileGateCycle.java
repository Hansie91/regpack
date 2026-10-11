package dev.regpack.spikes.x.bench;

import dev.regpack.bench.proto.Workload;
import java.nio.file.Path;
import java.util.Objects;
import javax.xml.validation.Schema;

/**
 * The whole-file gate on its own, in whole cycles: one cycle validates one file of {@code records}
 * records with one streaming validator ({@link FileGateBench#validateNanos}), and its units are the
 * records of the file. The file is made by the caller, untimed ({@link FileGateBench#generate}); it
 * is only read, so several threads may validate the same file. A file that is not valid fails the
 * benchmark.
 */
public final class FileGateCycle implements Workload {
  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private final Path file;
  private final Schema schema;
  private final int records;
  private long checksum;

  public FileGateCycle(Path file, Schema schema, int records) {
    this.file = Objects.requireNonNull(file, "file");
    this.schema = Objects.requireNonNull(schema, "schema");
    if (records < 1) {
      throw new IllegalArgumentException("records must be at least 1: " + records);
    }
    this.records = records;
  }

  @Override
  public long cycleUnits() {
    return records;
  }

  @Override
  public long runUnits(long maxUnits) {
    if (maxUnits < 0) {
      throw new IllegalArgumentException("maxUnits must not be negative: " + maxUnits);
    }
    long cycles = maxUnits / records;
    for (long c = 0; c < cycles; c++) {
      long nanos = FileGateBench.validateNanos(file, schema);
      // the elapsed time is not folded in (it is not deterministic); a passed gate is
      checksum = (checksum ^ (nanos > 0 ? 1L : 0L)) * GOLDEN + records;
    }
    return cycles * records;
  }

  @Override
  public long checksum() {
    return checksum;
  }
}
