package dev.regpack.ports.evidence.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** FORMAT-4: a journal object is at most 64 MiB, and a longer input is refused unread. */
class JournalObjectSizeTest {
  private static final int LIMIT = 67_108_864;
  private static final String TOO_LONG_TO_READ =
      "malformed journal object: the input is over 67108864 bytes";
  private static final String TOO_LONG_TO_WRITE =
      "journal object: the canonical bytes are over 67108864 bytes";

  @Test
  void theLimitIsThePinnedSixtyFourMebibytes() {
    assertThat(JournalObject.MAX_BYTES).isEqualTo(64 * 1024 * 1024).isEqualTo(LIMIT);
  }

  @Test
  void anObjectOfExactlyTheLimitIsWrittenAndReadBack() {
    JournalObject object = JournalFixtures.feedbackOfLength(LIMIT);
    byte[] bytes = object.toBytes();
    assertThat(bytes).hasSize(LIMIT);
    assertThat(JournalObject.fromBytes(bytes)).isEqualTo(object);
  }

  @Test
  void anObjectOneByteOverTheLimitCannotBeWritten() {
    JournalObject object = JournalFixtures.feedbackOfLength(LIMIT + 1L);
    assertThatThrownBy(object::toBytes)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TOO_LONG_TO_WRITE);
  }

  @Test
  void aLongerInputIsRefusedBeforeAnythingIsParsed() {
    // not UTF-8 and not JSON: only a check of the length before the parse gives this message
    byte[] over = new byte[LIMIT + 1];
    Arrays.fill(over, (byte) 0xFF);
    assertThatThrownBy(() -> JournalObject.fromBytes(over))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TOO_LONG_TO_READ);
    // the limit itself is read (and found to be no journal object): another message
    byte[] atLimit = Arrays.copyOf(over, LIMIT);
    assertThatThrownBy(() -> JournalObject.fromBytes(atLimit))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("67108864");
    // a whole object followed by one byte is over the limit too
    byte[] valid = JournalFixtures.feedbackOfLength(LIMIT).toBytes();
    byte[] plusOne = Arrays.copyOf(valid, LIMIT + 1);
    plusOne[LIMIT] = ' ';
    assertThatThrownBy(() -> JournalObject.fromBytes(plusOne))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TOO_LONG_TO_READ);
  }
}
