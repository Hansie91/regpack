package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.model.IngestKey;
import dev.regpack.ports.ledger.IngestLedger.Disposition;
import dev.regpack.ports.ledger.IngestLedger.EntryResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The stored form of the result of an ingest commit ({@code ingest_batch.result}): the dispositions
 * and keys of every entry in input order, so that a redelivery and a recovery read the original
 * result back without touching the large {@code ingest} table.
 */
class IngestResultCodecTest {
  private static final String SRC = "srcA";

  private static EntryResult result(String record, long version, Disposition disposition) {
    return new EntryResult(new IngestKey(SRC, record, version), disposition);
  }

  @Test
  void everyDispositionAndKeyRoundTripsInInputOrder() {
    List<EntryResult> entries = new ArrayList<>();
    Disposition[] all = Disposition.values();
    for (int i = 0; i < 40; i++) {
      entries.add(result("record-" + (39 - i), i * 1_000_003L, all[i % all.length]));
    }
    byte[] stored = IngestResultCodec.encode(entries);
    assertThat(stored[0]).isEqualTo((byte) IngestResultCodec.VERSION);
    assertThat(IngestResultCodec.decode(SRC, stored)).isEqualTo(entries);
  }

  @Test
  void anEmptyResultAndTheExtremesOfAKeyRoundTrip() {
    assertThat(IngestResultCodec.decode(SRC, IngestResultCodec.encode(List.of()))).isEmpty();
    List<EntryResult> extremes =
        List.of(
            result("a", 0, Disposition.NEW_VERSION),
            result("b", Long.MAX_VALUE, Disposition.STALE),
            // FP2: a record id is at most 256 chars (768 bytes), so its length takes two varint
            // bytes at most; the lengths around the one-byte limit of 127 are the boundary
            result("x".repeat(127), 127, Disposition.SOURCE_CONFLICT),
            result("y".repeat(128), 127, Disposition.SOURCE_CONFLICT),
            result("€".repeat(256), 127, Disposition.SOURCE_CONFLICT),
            result("128", 128, Disposition.NOOP_SAME_VERSION_SAME_HASH),
            result("16384", 16_384, Disposition.CONFLICT_SAME_VERSION_DIFFERENT_HASH));
    assertThat(IngestResultCodec.decode(SRC, IngestResultCodec.encode(extremes)))
        .isEqualTo(extremes);
  }

  @Test
  void textThatIsNotAsciiRoundTripsAsUtf8() {
    List<EntryResult> entries =
        List.of(
            result("Täxt-€-😀", 3, Disposition.NEW_VERSION), result("ıİi", 4, Disposition.STALE));
    assertThat(IngestResultCodec.decode(SRC, IngestResultCodec.encode(entries))).isEqualTo(entries);
  }

  @Test
  void aRandomResultRoundTrips() {
    Random random = new Random(20261018L);
    Disposition[] all = Disposition.values();
    List<EntryResult> entries = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      entries.add(
          result(
              Long.toString(random.nextLong(), 36),
              random.nextLong() & Long.MAX_VALUE,
              all[random.nextInt(all.length)]));
    }
    assertThat(IngestResultCodec.decode(SRC, IngestResultCodec.encode(entries))).isEqualTo(entries);
  }

  @Test
  void anUnknownVersionATruncationATrailingByteAndABadCodeAreRefused() {
    byte[] stored =
        IngestResultCodec.encode(
            List.of(result("r1", 1, Disposition.NEW_VERSION), result("r2", 2, Disposition.STALE)));
    byte[] otherVersion = stored.clone();
    otherVersion[0] = (byte) (IngestResultCodec.VERSION + 1);
    assertThatThrownBy(() -> IngestResultCodec.decode(SRC, otherVersion))
        .isInstanceOf(IllegalArgumentException.class);
    for (int length = 0; length < stored.length; length++) {
      byte[] cut = Arrays.copyOf(stored, length);
      assertThatThrownBy(() -> IngestResultCodec.decode(SRC, cut))
          .as("truncated to %d bytes", length)
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] trailing = Arrays.copyOf(stored, stored.length + 1);
    assertThatThrownBy(() -> IngestResultCodec.decode(SRC, trailing))
        .isInstanceOf(IllegalArgumentException.class);
    // the first entry's code byte follows the version byte and the count
    byte[] badCode = stored.clone();
    badCode[2] = 99;
    assertThatThrownBy(() -> IngestResultCodec.decode(SRC, badCode))
        .isInstanceOf(IllegalArgumentException.class);
    // a count that promises more entries than the bytes can hold
    byte[] hugeCount = {(byte) IngestResultCodec.VERSION, (byte) 0xFF, (byte) 0xFF, 0x7F};
    assertThatThrownBy(() -> IngestResultCodec.decode(SRC, hugeCount))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theStoredBytesOfAKnownResultArePinned() {
    // version 1, two entries; (code 1, length 2, "r1", version 1) and (code 3, length 2, "r2",
    // version 300 as the varint 0xAC 0x02): any change of this form needs VERSION 2
    byte[] expected = {1, 2, 1, 2, 'r', '1', 1, 3, 2, 'r', '2', (byte) 0xAC, 0x02};
    assertThat(
            IngestResultCodec.encode(
                List.of(
                    result("r1", 1, Disposition.NEW_VERSION),
                    result("r2", 300, Disposition.STALE))))
        .isEqualTo(expected);
  }
}
