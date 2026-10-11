package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The text of COPY and of the batched INSERT: PostgreSQL parses it, so the escapes must be exact.
 */
class CopyTextTest {
  @Test
  void aRowIsTabSeparatedAndEndsWithALineFeed() {
    Object[] row = {1L, 2, "abc", LocalDate.of(2026, 10, 9), true, null};
    assertThat(CopyText.line(row)).isEqualTo("1\t2\tabc\t2026-10-09\ttrue\t\\N\n");
  }

  @Test
  void textEscapesBackslashTabNewlineAndCarriageReturn() {
    assertThat(CopyText.line(new Object[] {"a\\b\tc\nd\re"})).isEqualTo("a\\\\b\\tc\\nd\\re\n");
  }

  @Test
  void byteaIsHexWithADoubledBackslashBecauseCopyUnescapesOnce() {
    // COPY removes one level of backslash: \\x0aff becomes \x0aff, the hex form of bytea
    assertThat(CopyText.line(new Object[] {new byte[] {0x0a, (byte) 0xff, 0x00}}))
        .isEqualTo("\\\\x0aff00\n");
    assertThat(CopyText.line(new Object[] {new byte[0]})).isEqualTo("\\\\x\n");
  }

  @Test
  void jsonIsEscapedLikeText() {
    assertThat(CopyText.line(new Object[] {new MicroBatchWriter.Jsonb("[{\"a\":\"x\\\\y\"}]")}))
        .isEqualTo("[{\"a\":\"x\\\\\\\\y\"}]\n");
  }

  @Test
  void anUnsupportedTypeIsRejected() {
    assertThatThrownBy(() -> CopyText.line(new Object[] {1.5d}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theStatementsNameTheColumnsAndUseTheDefaultTextFormat() {
    assertThat(CopyText.copySql("\"s\".ingest", List.of("a", "b")))
        .isEqualTo("COPY \"s\".ingest (a, b) FROM STDIN");
    assertThat(BulkLoader.insertSql("\"s\".ingest", List.of("a", "b", "c")))
        .isEqualTo("INSERT INTO \"s\".ingest (a, b, c) VALUES (?, ?, ?)");
  }
}
