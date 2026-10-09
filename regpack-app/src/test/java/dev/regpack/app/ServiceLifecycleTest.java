package dev.regpack.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ServiceLifecycleTest {
  private static final AppConfig PORT_ZERO = AppConfig.parse(List.of("--port", "0"), Map.of());

  private static String text(ByteArrayOutputStream bytes) {
    synchronized (bytes) {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }

  @Test
  void stopCompletesAndPrintsStoppedCleanly() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream log = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    Service service = Service.start(PORT_ZERO, log);
    int port = service.port();
    assertThat(port).isPositive();
    service.stop();
    service.stop();
    assertTimeoutPreemptively(Duration.ofSeconds(5), service::awaitStop);
    String expectedRoles = String.join(",", roleFlags(PORT_ZERO));
    assertThat(text(bytes))
        .isEqualTo(
            "regpack lifecycle: starting roles="
                + expectedRoles
                + " bind=127.0.0.1 port=0\n"
                + "regpack lifecycle: ready port="
                + port
                + "\n"
                + "regpack lifecycle: stopping\n"
                + "regpack lifecycle: stopped cleanly\n");
  }

  @Test
  void awaitStopBlocksUntilStop() throws Exception {
    Service service =
        Service.start(
            PORT_ZERO, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    CountDownLatch released = new CountDownLatch(1);
    Thread waiter =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    service.awaitStop();
                    released.countDown();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertThat(released.await(300, TimeUnit.MILLISECONDS)).isFalse();
    service.stop();
    assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
    waiter.join(5000);
  }

  @Test
  void windowsEntryStartBlocksUntilStopIsCalled() throws Exception {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    try {
      Thread entry =
          Thread.ofPlatform().start(() -> WindowsServiceEntry.start(new String[] {"--port", "0"}));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!text(bytes).contains("regpack lifecycle: ready") && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertThat(text(bytes)).contains("regpack lifecycle: ready");
      assertThat(entry.isAlive()).isTrue();
      WindowsServiceEntry.stop(new String[0]);
      entry.join(10_000);
      assertThat(entry.isAlive()).isFalse();
      assertThat(text(bytes)).contains("regpack lifecycle: stopped cleanly");
    } finally {
      System.setOut(originalOut);
    }
  }

  private static List<String> roleFlags(AppConfig config) {
    return config.roles().roles().stream().map(Role::flag).toList();
  }
}
