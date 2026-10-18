package dev.regpack.spikes.l.load;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowRatesTest {
  private static final long SECOND = 1_000_000_000L;

  @Test
  void recordsPerSecondOfEachFullWindow() {
    // three batches of 600 records in window 0, one of 1,200 in window 1, one after the last window
    long[] completions = {1 * SECOND, 30 * SECOND, 59 * SECOND, 61 * SECOND, 500 * SECOND};
    long[] records = {600, 600, 600, 1_200, 99};
    double[] rates = WindowRates.recordsPerSecond(completions, records, 60 * SECOND, 2);
    assertThat(rates).containsExactly(30.0, 20.0);
  }

  @Test
  void aCompletionExactlyOnTheEdgeBelongsToTheNextWindow() {
    double[] rates =
        WindowRates.recordsPerSecond(new long[] {60 * SECOND}, new long[] {600}, 60 * SECOND, 2);
    assertThat(rates).containsExactly(0.0, 10.0);
  }

  @Test
  void aShortRunHasNoFullWindow() {
    assertThat(WindowRates.fullWindows(59 * SECOND, 60 * SECOND)).isZero();
    assertThat(WindowRates.fullWindows(600 * SECOND, 60 * SECOND)).isEqualTo(10);
    assertThat(WindowRates.fullWindows(1_199 * SECOND, 60 * SECOND)).isEqualTo(19);
  }
}
