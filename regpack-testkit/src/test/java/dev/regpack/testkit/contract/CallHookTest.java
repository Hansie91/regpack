package dev.regpack.testkit.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallHookTest {
  interface Port {
    String put(String value);

    int get();
  }

  private static final class Recording implements Port {
    final List<String> events = new ArrayList<>();
    RuntimeException failure;

    @Override
    public String put(String value) {
      events.add("put " + value);
      if (failure != null) {
        throw failure;
      }
      return "stored " + value;
    }

    @Override
    public int get() {
      events.add("get");
      return 7;
    }

    @Override
    public String toString() {
      return "recording";
    }
  }

  @Test
  void beforeRunsBeforeEachCallOfTheMethodAndCountsFromOne() {
    Recording delegate = new Recording();
    Port hooked =
        CallHook.before(Port.class, delegate, "put", hit -> delegate.events.add("before #" + hit));

    assertThat(hooked.put("a")).isEqualTo("stored a");
    assertThat(hooked.put("b")).isEqualTo("stored b");

    assertThat(delegate.events).containsExactly("before #1", "put a", "before #2", "put b");
  }

  @Test
  void afterRunsAfterTheCallHasReturnedAndNotWhenItThrew() {
    Recording delegate = new Recording();
    Port hooked =
        CallHook.after(Port.class, delegate, "put", hit -> delegate.events.add("after #" + hit));

    hooked.put("a");
    delegate.failure = new IllegalStateException("boom");
    assertThatThrownBy(() -> hooked.put("b"))
        .isSameAs(delegate.failure)
        .isInstanceOf(IllegalStateException.class);

    assertThat(delegate.events).containsExactly("put a", "after #1", "put b");
  }

  @Test
  void otherMethodsAreNeitherHookedNorCounted() {
    Recording delegate = new Recording();
    Port hooked =
        CallHook.after(Port.class, delegate, "put", hit -> delegate.events.add("after #" + hit));

    assertThat(hooked.get()).isEqualTo(7);
    hooked.put("a");
    assertThat(hooked.get()).isEqualTo(7);

    assertThat(delegate.events).containsExactly("get", "put a", "after #1", "get");
  }

  @Test
  void anExceptionOfABeforeActionStopsTheCallAndOneOfAnAfterActionStillPropagates() {
    Recording delegate = new Recording();
    Port refused =
        CallHook.before(
            Port.class,
            delegate,
            "put",
            hit -> {
              throw new IllegalArgumentException("no");
            });
    Port late =
        CallHook.after(
            Port.class,
            delegate,
            "put",
            hit -> {
              throw new IllegalArgumentException("too late");
            });

    assertThatThrownBy(() -> refused.put("a")).isInstanceOf(IllegalArgumentException.class);
    assertThat(delegate.events).as("the call was never made").isEmpty();
    assertThatThrownBy(() -> late.put("b")).isInstanceOf(IllegalArgumentException.class);
    assertThat(delegate.events).as("the call was made").containsExactly("put b");
  }

  @Test
  void theProxyHasIdentityEqualityAndThePrintedFormOfTheDelegate() {
    Recording delegate = new Recording();
    Port hooked = CallHook.before(Port.class, delegate, "put", hit -> {});
    Port other = CallHook.before(Port.class, delegate, "put", hit -> {});

    assertThat(hooked).isEqualTo(hooked).isNotEqualTo(other).hasSameHashCodeAs(hooked);
    assertThat(hooked).hasToString("recording");
  }

  @Test
  void aPortThatIsNoInterfaceIsRefused() {
    assertThatThrownBy(() -> CallHook.before(Recording.class, new Recording(), "put", hit -> {}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
