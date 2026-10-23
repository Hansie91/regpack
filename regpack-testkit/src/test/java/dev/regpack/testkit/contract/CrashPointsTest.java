package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrashPointsTest {
  @Test
  void noCrashPointIsOnACallThatTheSealerReadsFailClosed() {
    List<String> onFailClosedReads = new ArrayList<>();
    for (String point : CrashPoints.ALL) {
      if (CrashPoints.onAFailClosedRead(point)) {
        onFailClosedReads.add(point);
      }
    }
    assertThat(onFailClosedReads)
        .as("a crash there is swallowed as 'unreadable' by the gate and the epoch read")
        .isEmpty();
  }

  @Test
  void theRegistryHoldsEveryPublicStringConstantOfTheClass() throws IllegalAccessException {
    List<String> declared = new ArrayList<>();
    for (Field field : CrashPoints.class.getDeclaredFields()) {
      if (field.getType() == String.class
          && Modifier.isPublic(field.getModifiers())
          && Modifier.isStatic(field.getModifiers())) {
        declared.add((String) field.get(null));
      }
    }
    assertThat(declared).isNotEmpty();
    assertThat(CrashPoints.ALL).containsAll(declared);
    assertThat(CrashPoints.ALL).doesNotHaveDuplicates().hasSize(4);
  }

  @Test
  void theCheckRecognisesTheCallsItGuards() {
    for (String call :
        List.of(
            "EvidenceStore.list",
            "EvidenceStore.get",
            "EvidenceStore.durability",
            "LedgerStore.durability",
            "LedgerDurability.durability")) {
      assertThat(CrashPoints.onAFailClosedRead(call + ":BEFORE")).as(call + ":BEFORE").isTrue();
      assertThat(CrashPoints.onAFailClosedRead(call + ":AFTER")).as(call + ":AFTER").isTrue();
    }
    assertThat(CrashPoints.onAFailClosedRead("EvidenceStore.put:AFTER")).isFalse();
    assertThat(CrashPoints.onAFailClosedRead("LedgerStore.markCommitIssued:AFTER")).isFalse();
  }
}
