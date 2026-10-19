package dev.regpack.postgres;

import dev.regpack.model.IngestKey;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The stored form of the result of an ingest commit, {@code ingest_batch.result}: the disposition
 * and the key of every entry, in the order of the commit. A redelivery of the batch and a recovery
 * that reads the result back need exactly that, and need it without a scan of the {@code ingest}
 * table, which has no index on the batch number and holds only the new entries.
 *
 * <p>Layout, all integers unsigned LEB128 varints: the version byte ({@link #VERSION}), the number
 * of entries, then per entry the disposition code (one byte, {@link Codes}), the length of the
 * source record id in UTF-8, its bytes, and the source version. The source id is not repeated: it
 * is the one of the batch. A change of the layout needs a new version byte, and the decoder refuses
 * anything it does not know: an unknown version, a truncation, trailing bytes, an unknown code.
 */
final class IngestResultCodec {
  static final int VERSION = 1;

  /** The least bytes one entry takes: its code, a length byte and a version byte. */
  private static final int MIN_ENTRY_BYTES = 3;

  private IngestResultCodec() {}

  static byte[] encode(List<EntryResult> entries) {
    Objects.requireNonNull(entries, "entries");
    ByteArrayOutputStream out = new ByteArrayOutputStream(16 + entries.size() * 24);
    out.write(VERSION);
    writeVarint(out, entries.size());
    for (EntryResult entry : entries) {
      byte[] record = entry.key().sourceRecordId().getBytes(StandardCharsets.UTF_8);
      out.write(Codes.of(entry.disposition()));
      writeVarint(out, record.length);
      out.writeBytes(record);
      writeVarint(out, entry.key().sourceVersion());
    }
    return out.toByteArray();
  }

  /**
   * @param sourceId the source of the batch, the source id of every decoded key
   * @throws IllegalArgumentException for anything that {@link #encode} cannot have written
   */
  static List<EntryResult> decode(String sourceId, byte[] stored) {
    Objects.requireNonNull(sourceId, "sourceId");
    Reader in = new Reader(Objects.requireNonNull(stored, "stored"));
    if (in.u8() != VERSION) {
      throw new IllegalArgumentException("unknown ingest result version");
    }
    long count = in.varint();
    if (count > in.remaining() / MIN_ENTRY_BYTES) {
      throw new IllegalArgumentException("ingest result is truncated");
    }
    List<EntryResult> entries = new ArrayList<>((int) count);
    for (long i = 0; i < count; i++) {
      short code = (short) in.u8();
      long length = in.varint();
      if (length > in.remaining()) {
        throw new IllegalArgumentException("ingest result is truncated");
      }
      String record = in.string((int) length);
      long version = in.varint();
      entries.add(
          new EntryResult(new IngestKey(sourceId, record, version), Codes.disposition(code)));
    }
    if (in.remaining() != 0) {
      throw new IllegalArgumentException("ingest result has trailing bytes");
    }
    return List.copyOf(entries);
  }

  private static void writeVarint(ByteArrayOutputStream out, long value) {
    long rest = value;
    while ((rest & ~0x7FL) != 0) {
      out.write((int) ((rest & 0x7F) | 0x80));
      rest >>>= 7;
    }
    out.write((int) rest);
  }

  /** A cursor over the stored bytes that refuses to read past the end. */
  private static final class Reader {
    private final byte[] bytes;
    private int position;

    Reader(byte[] bytes) {
      this.bytes = bytes;
    }

    int remaining() {
      return bytes.length - position;
    }

    int u8() {
      if (position >= bytes.length) {
        throw new IllegalArgumentException("ingest result is truncated");
      }
      return bytes[position++] & 0xFF;
    }

    /** A varint of at most nine bytes (63 bits): a version or a length is never negative. */
    long varint() {
      long value = 0;
      for (int shift = 0; shift < 63; shift += 7) {
        int next = u8();
        value |= (long) (next & 0x7F) << shift;
        if ((next & 0x80) == 0) {
          if (value < 0) {
            throw new IllegalArgumentException("ingest result holds a number out of range");
          }
          return value;
        }
      }
      throw new IllegalArgumentException("ingest result holds a number out of range");
    }

    String string(int length) {
      String text = new String(bytes, position, length, StandardCharsets.UTF_8);
      position += length;
      return text;
    }
  }
}
