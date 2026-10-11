package dev.regpack.spikes.l.pg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.spikes.l.pg.SyncPrecondition.Sample;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The standby precondition of SP-L (review focus RF14): group commit only counts with a live
 * standby.
 */
class SyncPreconditionTest {
  private static Sample sample(String name, String syncState, String state) {
    return new Sample(name, syncState, state);
  }

  @Test
  void everySampleMustShowSyncOrQuorumStandby() {
    assertThat(SyncPrecondition.violations(List.of())).containsExactly("NO_SYNC_STANDBY");
    assertThat(SyncPrecondition.violations(List.of(sample("replica1", "async", "streaming"))))
        .containsExactly("NO_SYNC_STANDBY");
    assertThat(SyncPrecondition.violations(List.of(sample("standby1", "potential", "streaming"))))
        .containsExactly("NO_SYNC_STANDBY");
    assertThat(SyncPrecondition.violations(List.of(sample("standby1", "quorum", "streaming"))))
        .isEmpty();
    assertThat(SyncPrecondition.violations(List.of(sample("standby1", "sync", "streaming"))))
        .isEmpty();
  }

  @Test
  void anAsynchronousReplicaBesideTheQuorumStandbyIsNotAViolation() {
    assertThat(
            SyncPrecondition.violations(
                List.of(
                    sample("replica1", "async", "streaming"),
                    sample("standby1", "quorum", "streaming"))))
        .isEmpty();
  }

  @Test
  void violationsNameTheProblem() {
    assertThat(SyncPrecondition.violations(List.of(sample("standby1", "quorum", "catchup"))))
        .containsExactly("STANDBY_NOT_STREAMING");
    assertThat(SyncPrecondition.violations(List.of(sample("standby1", "sync", "startup"))))
        .containsExactly("STANDBY_NOT_STREAMING");
  }

  @Test
  void aCatchingUpQuorumStandbyIsAViolationEvenWhenAnotherOneStreams() {
    List<String> violations =
        SyncPrecondition.violations(
            List.of(
                sample("standby1", "quorum", "streaming"),
                sample("standby2", "quorum", "catchup")));
    assertThat(violations).containsExactly("STANDBY_NOT_STREAMING");
  }

  @Test
  void aNonStreamingAsyncReplicaDoesNotMatter() {
    assertThat(
            SyncPrecondition.violations(
                List.of(
                    sample("replica1", "async", "catchup"),
                    sample("standby1", "quorum", "streaming"))))
        .isEmpty();
  }

  @Test
  void theResultIsAnImmutableCopy() {
    List<String> violations = SyncPrecondition.violations(List.of());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> violations.add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
