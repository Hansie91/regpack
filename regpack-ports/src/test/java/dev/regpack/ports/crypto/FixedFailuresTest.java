package dev.regpack.ports.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.regpack.ports.security.KmsKeyDestroyedException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The failures of the crypto package carry a fixed text and nothing that a backend could add. */
class FixedFailuresTest {
  private static List<RuntimeException> failures() {
    return List.of(
        new Envelope.AuthenticationException(),
        new DataShreddedException(),
        new KeyUnavailableException());
  }

  @Test
  void theMessagesArePinned() {
    assertThat(new Envelope.AuthenticationException())
        .hasMessage("the envelope could not be authenticated");
    assertThat(new DataShreddedException())
        .hasMessage("the key that protects this data was destroyed");
    assertThat(new KeyUnavailableException())
        .hasMessage("the key service could not serve this request");
    assertThat(new KmsKeyDestroyedException())
        .hasMessage("the key has been destroyed")
        .hasNoCause();
  }

  @Test
  void noCauseAndNoSuppressedExceptionCanBeAttached() {
    for (RuntimeException failure : failures()) {
      String name = failure.getClass().getSimpleName();

      failure.addSuppressed(new IllegalStateException("backend text"));

      assertThat(failure.getSuppressed()).as("%s suppressed", name).isEmpty();
      assertThatThrownBy(() -> failure.initCause(new IllegalStateException("backend text")))
          .as("%s initCause", name)
          .isInstanceOf(IllegalStateException.class);
      assertThat(failure.getCause()).as("%s cause", name).isNull();
      assertThat(failure.toString()).as("%s text", name).doesNotContain("backend text");
    }
  }
}
