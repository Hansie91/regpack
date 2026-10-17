package dev.regpack.pack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The limits of the pack format are pinned: a limit changes only by a deliberate edit. */
class PackFormatTest {
  @Test
  void limitsArePinned() {
    assertThat(PackFormat.VERSION).isEqualTo(1);
    assertThat(PackFormat.MAX_FILE_BYTES).isEqualTo(1 << 20);
    assertThat(PackFormat.MAX_FILES).isEqualTo(256);
    assertThat(PackFormat.MAX_TOTAL_BYTES).isEqualTo(16 << 20);
    assertThat(PackFormat.MAX_LINE_CHARS).isEqualTo(8192);
    assertThat(PackFormat.MAX_SCALAR_CHARS).isEqualTo(8192);
    assertThat(PackFormat.MAX_KEY_CHARS).isEqualTo(128);
    assertThat(PackFormat.MAX_DEPTH).isEqualTo(32);
    assertThat(PackFormat.MAX_NODES).isEqualTo(100_000);
    assertThat(PackFormat.MAX_PROBLEMS_PER_FILE).isEqualTo(100);
    assertThat(PackFormat.MAX_PROBLEMS_PER_LOAD).isEqualTo(1_000);
    assertThat(PackFormat.MAX_RULE_NODES).isEqualTo(4_096);
    assertThat(PackFormat.MAX_LIST_ITEMS).isEqualTo(4_096);
    assertThat(PackFormat.MAX_STRING_CODE_POINTS).isEqualTo(65_536);
    assertThat(PackFormat.MAX_RECORD_BUDGET).isEqualTo(10_000_000L);
  }
}
