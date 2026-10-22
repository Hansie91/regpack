package dev.regpack.ports.evidence.journal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * FORMAT-4 and JOURNAL-1: a journal object is at most 64 MiB for the two types whose payload is a
 * list that grows with a batch (VersionAssigned, BatchAdmitted) and at most 1 MiB for every other
 * type, and a longer input is refused unread: the bytes are bounded before the parser builds a tree
 * that is many times larger than they are.
 */
class JournalObjectSizeTest {
  private static final int LARGE = 67_108_864;
  private static final int SMALL = 1_048_576;
  private static final String LARGE_TO_READ =
      "malformed journal object: the input is over 67108864 bytes";
  private static final String SMALL_TO_READ =
      "malformed journal object: the input is over 1048576 bytes";
  private static final String LARGE_TO_WRITE =
      "journal object: the canonical bytes are over 67108864 bytes";
  private static final String SMALL_TO_WRITE =
      "journal object: the canonical bytes are over 1048576 bytes";

  private static final JournalKey INTENT_KEY =
      JournalFixtures.object(JournalType.DELIVERY_INTENT).key();
  private static final JournalKey BATCH_KEY =
      JournalFixtures.object(JournalType.BATCH_ADMITTED).key();
  private static final JournalKey VERSIONS_KEY =
      JournalFixtures.object(JournalType.VERSION_ASSIGNED).key();

  @Test
  void theLimitsAreThePinnedSixtyFourMebibytesAndOneMebibyte() {
    assertThat(JournalObject.MAX_BYTES).isEqualTo(64 * 1024 * 1024).isEqualTo(LARGE);
    assertThat(JournalObject.MAX_SMALL_BYTES).isEqualTo(1024 * 1024).isEqualTo(SMALL);
    for (JournalType type : JournalType.values()) {
      boolean large = type == JournalType.VERSION_ASSIGNED || type == JournalType.BATCH_ADMITTED;
      assertThat(type.maxBytes()).as(type.wireName()).isEqualTo(large ? LARGE : SMALL);
    }
  }

  @Test
  void aLargeTypeOfExactlyTheLimitIsWrittenAndReadBack() {
    JournalObject object = JournalFixtures.versionAssignedOfLength(LARGE);
    byte[] bytes = object.toBytes();
    assertThat(bytes).hasSize(LARGE);
    assertThat(JournalObject.fromBytes(object.key(), bytes)).isEqualTo(object);
  }

  @Test
  void aLargeTypeOneByteOverTheLimitCannotBeWritten() {
    JournalObject object = JournalFixtures.versionAssignedOfLength(LARGE + 1L);
    assertThatThrownBy(object::toBytes)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(LARGE_TO_WRITE);
  }

  @Test
  void aSmallTypeOfExactlyItsLimitIsWrittenAndReadBack() {
    JournalObject object = JournalFixtures.feedbackOfLength(SMALL);
    byte[] bytes = object.toBytes();
    assertThat(bytes).hasSize(SMALL);
    assertThat(JournalObject.fromBytes(object.key(), bytes)).isEqualTo(object);
  }

  @Test
  void aSmallTypeOneByteOverItsLimitCannotBeWrittenAlthoughALargeTypeCanBeThatLong() {
    JournalObject object = JournalFixtures.feedbackOfLength(SMALL + 1L);
    assertThatThrownBy(object::toBytes)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SMALL_TO_WRITE);
    JournalObject large = JournalFixtures.versionAssignedOfLength(SMALL + 1L);
    assertThat(large.toBytes()).hasSize(SMALL + 1);
  }

  @Test
  void aLongerInputIsRefusedBeforeAnythingIsParsed() {
    // not UTF-8 and not JSON: only a check of the length before the parse gives this message
    byte[] overLarge = new byte[LARGE + 1];
    Arrays.fill(overLarge, (byte) 0xFF);
    assertThatThrownBy(() -> JournalObject.fromBytes(VERSIONS_KEY, overLarge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(LARGE_TO_READ);
    assertThatThrownBy(() -> JournalObject.fromBytes(BATCH_KEY, overLarge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(LARGE_TO_READ);
    assertThatThrownBy(() -> JournalObject.fromBytes(INTENT_KEY, overLarge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SMALL_TO_READ);
    // the limit itself is read (and found to be no journal object): another message
    byte[] atLarge = Arrays.copyOf(overLarge, LARGE);
    assertThatThrownBy(() -> JournalObject.fromBytes(VERSIONS_KEY, atLarge))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("67108864");
    // a whole object followed by one byte is over the limit too
    byte[] valid = JournalFixtures.versionAssignedOfLength(LARGE).toBytes();
    byte[] plusOne = Arrays.copyOf(valid, LARGE + 1);
    plusOne[LARGE] = ' ';
    assertThatThrownBy(() -> JournalObject.fromBytes(VERSIONS_KEY, plusOne))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(LARGE_TO_READ);
  }

  @Test
  void aSmallTypesKeyBoundsTheInputAtOneMebibyteAndALargeTypesKeyDoesNot() {
    byte[] junk = new byte[SMALL + 1];
    Arrays.fill(junk, (byte) 0xFF);
    // under a DeliveryIntent key: refused unread
    assertThatThrownBy(() -> JournalObject.fromBytes(INTENT_KEY, junk))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SMALL_TO_READ);
    // under the keys of the large types (class a BatchAdmitted, class b): the normal checks run
    // and find the bytes to be no journal object
    assertThatThrownBy(() -> JournalObject.fromBytes(BATCH_KEY, junk))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("malformed journal object: the text cannot be read");
    assertThatThrownBy(() -> JournalObject.fromBytes(VERSIONS_KEY, junk))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("malformed journal object: the text cannot be read");
    // exactly one mebibyte under a DeliveryIntent key is read, not refused unread
    byte[] atSmall = Arrays.copyOf(junk, SMALL);
    assertThatThrownBy(() -> JournalObject.fromBytes(INTENT_KEY, atSmall))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("1048576");
    // a real VersionAssigned object of one mebibyte and a byte is read in full
    JournalObject large = JournalFixtures.versionAssignedOfLength(SMALL + 1L);
    assertThat(JournalObject.fromBytes(large.key(), large.toBytes())).isEqualTo(large);
    // the same bytes under a DeliveryIntent key are refused before they are read
    assertThatThrownBy(() -> JournalObject.fromBytes(INTENT_KEY, large.toBytes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(SMALL_TO_READ);
  }

  @Test
  void aDenseHostileInputUnderASmallTypesKeyIsRefusedWithoutBuildingAnyTree() {
    // the hostile object of JOURNAL-1: a dense list of tiny nodes after a well-formed head; 64 MiB
    // of it would be a tree of 22 million nodes (more than a gigabyte of heap) in the parser, and
    // the refusal before the parse allocates next to nothing
    byte[] head = "{\"envelope\":{},\"payload\":{},\"x\":[".getBytes(UTF_8);
    byte[] tail = "0]}".getBytes(UTF_8);
    int end = head.length + (LARGE - head.length - tail.length) / 3 * 3;
    byte[] hostile = new byte[end + tail.length];
    System.arraycopy(head, 0, hostile, 0, head.length);
    for (int i = head.length; i < end; i += 3) {
      hostile[i] = '{';
      hostile[i + 1] = '}';
      hostile[i + 2] = ',';
    }
    System.arraycopy(tail, 0, hostile, end, tail.length);
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    assertThat(threads.isThreadAllocatedMemorySupported()).isTrue();
    long before = threads.getCurrentThreadAllocatedBytes();
    Throwable refused = catchThrowable(() -> JournalObject.fromBytes(INTENT_KEY, hostile));
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    assertThat(allocated).as("bytes allocated by the refusal").isLessThan(SMALL);
    assertThat(refused).isInstanceOf(IllegalArgumentException.class).hasMessage(SMALL_TO_READ);
  }
}
