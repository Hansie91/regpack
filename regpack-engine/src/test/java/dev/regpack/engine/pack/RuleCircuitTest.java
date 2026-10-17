package dev.regpack.engine.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The per-rule poison circuit (spec 7.3). Everything here counts evaluations, never time: the
 * window is a number of evaluations of the rule.
 */
class RuleCircuitTest {

  private static void fail(RuleCircuit circuit, String rule, int times) {
    for (int i = 0; i < times; i++) {
      circuit.record(rule, true);
    }
  }

  private static void pass(RuleCircuit circuit, String rule, int times) {
    for (int i = 0; i < times; i++) {
      circuit.record(rule, false);
    }
  }

  @Test
  void defaultsAreFourFailuresInFifty() {
    RuleCircuit circuit = RuleCircuit.defaults();

    fail(circuit, "r", 3);
    assertThat(circuit.isOpen("r")).isFalse();
    circuit.record("r", true);

    assertThat(circuit.isOpen("r")).isTrue();
  }

  @Test
  void opensAfterNFailuresInOneWindow() {
    RuleCircuit circuit = new RuleCircuit(4, 50);

    pass(circuit, "r", 10);
    fail(circuit, "r", 3);
    assertThat(circuit.isOpen("r")).isFalse();
    pass(circuit, "r", 20);
    assertThat(circuit.isOpen("r")).isFalse();
    circuit.record("r", true);

    assertThat(circuit.isOpen("r")).isTrue();
    assertThat(circuit.openRules()).containsExactly("r");
  }

  @Test
  void doesNotOpenWhenFailuresAreSpreadOverWindows() {
    RuleCircuit circuit = new RuleCircuit(4, 50);

    // two windows of 50 evaluations, three failures in each: six failures in all, never four in one
    fail(circuit, "r", 3);
    pass(circuit, "r", 47);
    fail(circuit, "r", 3);
    pass(circuit, "r", 47);

    assertThat(circuit.isOpen("r")).isFalse();
    assertThat(circuit.openEvents()).isZero();
  }

  @Test
  void windowsTumblePerRule() {
    RuleCircuit circuit = new RuleCircuit(2, 3);

    // r: window 1 = fail, pass, pass (1 failure); window 2 = fail, pass, fail (2 failures: opens)
    circuit.record("r", true);
    pass(circuit, "r", 2);
    circuit.record("r", true);
    circuit.record("r", false);
    assertThat(circuit.isOpen("r")).isFalse();
    circuit.record("r", true);
    assertThat(circuit.isOpen("r")).isTrue();

    // s has its own window: a failure in the last slot of one window and one in the first slot of
    // the next are two windows, not two failures of one
    pass(circuit, "s", 2);
    circuit.record("s", true);
    circuit.record("s", true);
    assertThat(circuit.isOpen("s")).isFalse();
    pass(circuit, "s", 2);
    circuit.record("s", true);
    circuit.record("s", true);
    assertThat(circuit.isOpen("s")).isTrue();
  }

  @Test
  void rulesAreIndependent() {
    RuleCircuit circuit = new RuleCircuit(4, 50);

    fail(circuit, "bad", 4);
    pass(circuit, "good", 100);

    assertThat(circuit.isOpen("bad")).isTrue();
    assertThat(circuit.isOpen("good")).isFalse();
    assertThat(circuit.isOpen("never-seen")).isFalse();
    assertThat(circuit.openRules()).containsExactly("bad");
  }

  @Test
  void closingResetsTheWindow() {
    RuleCircuit circuit = new RuleCircuit(4, 50);
    fail(circuit, "r", 4);
    assertThat(circuit.isOpen("r")).isTrue();

    circuit.close("r");

    assertThat(circuit.isOpen("r")).isFalse();
    assertThat(circuit.openRules()).isEmpty();
    fail(circuit, "r", 3);
    assertThat(circuit.isOpen("r")).as("the old failures do not count").isFalse();
    circuit.record("r", true);
    assertThat(circuit.isOpen("r")).isTrue();
    circuit.close("never-seen");
    assertThat(circuit.isOpen("never-seen")).isFalse();
  }

  @Test
  void anOpenCircuitStaysOpenUntilItIsClosed() {
    RuleCircuit circuit = new RuleCircuit(2, 3);
    fail(circuit, "r", 2);

    // an evaluation reported for an open rule changes nothing (OI-4: explicit close only)
    pass(circuit, "r", 1000);

    assertThat(circuit.isOpen("r")).isTrue();
    assertThat(circuit.openEvents()).isEqualTo(1);
  }

  @Test
  void openEventsAreCounted() {
    RuleCircuit circuit = new RuleCircuit(2, 10);
    assertThat(circuit.openEvents()).isZero();

    fail(circuit, "a", 2);
    fail(circuit, "b", 2);
    assertThat(circuit.openEvents()).isEqualTo(2);
    fail(circuit, "a", 5);
    assertThat(circuit.openEvents()).as("an open rule does not open again").isEqualTo(2);
    circuit.close("a");
    fail(circuit, "a", 2);

    assertThat(circuit.openEvents()).isEqualTo(3);
    assertThat(circuit.openRules()).containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void openRulesIsACopy() {
    RuleCircuit circuit = new RuleCircuit(1, 1);
    circuit.record("a", true);
    Set<String> snapshot = circuit.openRules();

    circuit.record("b", true);

    assertThat(snapshot).containsExactly("a");
    assertThatThrownBy(() -> snapshot.add("x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theSameSequenceGivesTheSameResult() {
    List<List<String>> runs = new ArrayList<>();
    for (int run = 0; run < 2; run++) {
      RuleCircuit circuit = new RuleCircuit(4, 50);
      List<String> seen = new ArrayList<>();
      for (int i = 0; i < 400; i++) {
        String rule = "r" + (i % 3);
        circuit.record(rule, i % 7 == 0 || i % 11 == 0);
        seen.add(rule + ":" + circuit.isOpen(rule) + ":" + circuit.openEvents());
      }
      runs.add(seen);
    }

    assertThat(runs.get(1)).isEqualTo(runs.get(0));
    assertThat(runs.get(0).get(399)).contains("true");
  }

  @Test
  void concurrentRecordsNeverLoseACount() throws InterruptedException {
    RuleCircuit all = new RuleCircuit(8000, 8000);
    runConcurrently(all, 8, 1000);
    assertThat(all.isOpen("r")).isTrue();
    assertThat(all.openEvents()).isEqualTo(1);

    // 7,999 failing calls in total: one short of the 8,000 that open the rule
    RuleCircuit oneShort = new RuleCircuit(8000, 8000);
    runConcurrently(oneShort, 7, 1000);
    for (int i = 0; i < 999; i++) {
      oneShort.record("r", true);
    }
    assertThat(oneShort.isOpen("r")).isFalse();
    assertThat(oneShort.openEvents()).isZero();
    oneShort.record("r", true);
    assertThat(oneShort.isOpen("r")).isTrue();
  }

  private static void runConcurrently(RuleCircuit circuit, int threads, int calls)
      throws InterruptedException {
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();
    List<Thread> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int i = 0; i < calls; i++) {
                    circuit.record("r", true);
                  }
                } catch (Throwable e) {
                  error.compareAndSet(null, e);
                }
              });
      workers.add(worker);
      worker.start();
    }
    start.countDown();
    for (Thread worker : workers) {
      worker.join();
    }
    assertThat(error.get()).isNull();
  }

  @Test
  void rejectsNonPositiveOrInvertedParameters() {
    assertThatThrownBy(() -> new RuleCircuit(0, 50)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleCircuit(-1, 50)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleCircuit(4, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RuleCircuit(5, 4)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new RuleCircuit(4, 4).isOpen("r")).isFalse();
    assertThat(new RuleCircuit(1, 1).isOpen("r")).isFalse();
  }

  @Test
  void rejectsBlankRuleIds() {
    RuleCircuit circuit = RuleCircuit.defaults();
    assertThatThrownBy(() -> circuit.record(" ", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> circuit.isOpen("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> circuit.close(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
