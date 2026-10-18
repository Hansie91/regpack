package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T15 made every sub-port of the adapter a stub that throws {@code UnsupportedOperationException}
 * until its task; T16 to T18 replaced them one by one. This is the end mark: no source of the
 * module's main code names that exception any more, so no method is left unbuilt.
 */
class NoStubsLeftTest {
  private static Path mainSources() {
    Path root = Path.of(System.getProperty("regpack.repo.root")).toAbsolutePath().normalize();
    return root.resolve("regpack-postgres/src/main/java");
  }

  @Test
  void noMethodIsLeftUnbuilt() throws IOException {
    Path sources = mainSources();
    // the scan must see the adapter, or "no offenders" would be vacuous
    assertThat(sources.resolve("dev/regpack/postgres/PgLedgerStore.java")).isRegularFile();
    assertThat(sources.resolve("dev/regpack/postgres/migrate/Migrator.java")).isRegularFile();

    List<Path> files;
    try (Stream<Path> walk = Files.walk(sources)) {
      files = walk.filter(p -> p.toString().endsWith(".java")).toList();
    }
    assertThat(files).hasSizeGreaterThan(10);
    List<String> offenders = new ArrayList<>();
    for (Path file : files) {
      if (Files.readString(file, StandardCharsets.UTF_8)
          .contains("UnsupportedOperationException")) {
        offenders.add(sources.relativize(file).toString().replace('\\', '/'));
      }
    }
    assertThat(offenders)
        .as("main sources that still throw UnsupportedOperationException")
        .isEmpty();
  }
}
