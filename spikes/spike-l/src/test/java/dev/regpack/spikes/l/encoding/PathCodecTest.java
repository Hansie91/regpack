package dev.regpack.spikes.l.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PathCodecTest {
  private static final int STEPS = 50;

  private static int[] ordinals() {
    int[] out = new int[STEPS];
    for (int i = 0; i < STEPS; i++) {
      out[i] = 3 + i * 2 + (i % 3);
    }
    return out;
  }

  private static boolean[] matched() {
    boolean[] out = new boolean[STEPS];
    for (int i = 0; i < STEPS; i++) {
      out[i] = i % 7 == 0;
    }
    return out;
  }

  private static int[] costs() {
    int[] out = new int[STEPS];
    for (int i = 0; i < STEPS; i++) {
      out[i] = 1 + (i * 5) % 40;
    }
    return out;
  }

  @Test
  void roundTripsAndIsDeterministic() {
    byte[] first = PathCodec.encode(ordinals(), matched(), costs());
    byte[] second = PathCodec.encode(ordinals(), matched(), costs());
    assertThat(first).isEqualTo(second);
    assertThat(first.length).isLessThanOrEqualTo(150);

    PathCodec.Decoded decoded = PathCodec.decode(first);
    assertThat(decoded.ruleOrdinals()).containsExactly(ordinals());
    assertThat(decoded.matched()).containsExactly(matched());
    assertThat(decoded.costUnits()).containsExactly(costs());
  }

  @Test
  void anEmptyPathAndNonMonotoneOrdinalsRoundTrip() {
    PathCodec.Decoded empty =
        PathCodec.decode(PathCodec.encode(new int[0], new boolean[0], new int[0]));
    assertThat(empty.ruleOrdinals()).isEmpty();

    int[] ordinals = {900, 3, 3, 70_000, 0};
    boolean[] matched = {true, false, true, false, true};
    int[] costs = {0, 127, 128, 16_384, 2_000_000_000};
    PathCodec.Decoded decoded = PathCodec.decode(PathCodec.encode(ordinals, matched, costs));
    assertThat(decoded.ruleOrdinals()).containsExactly(ordinals);
    assertThat(decoded.matched()).containsExactly(matched);
    assertThat(decoded.costUnits()).containsExactly(costs);
  }

  @Test
  void badInputIsRejected() {
    assertThatThrownBy(
            () -> PathCodec.encode(new int[] {1}, new boolean[] {true, false}, new int[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PathCodec.encode(new int[] {-1}, new boolean[] {true}, new int[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PathCodec.encode(new int[] {1}, new boolean[] {true}, new int[] {-1}))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] good = PathCodec.encode(ordinals(), matched(), costs());
    byte[] truncated = Arrays.copyOf(good, good.length - 1);
    assertThatThrownBy(() -> PathCodec.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class);
    byte[] trailing = Arrays.copyOf(good, good.length + 1);
    assertThatThrownBy(() -> PathCodec.decode(trailing))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
