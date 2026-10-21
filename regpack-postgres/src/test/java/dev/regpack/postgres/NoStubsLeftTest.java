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
 *
 * <p>P3b1 added two sub-ports whose placeholders threw {@code IllegalStateException("not built
 * until T12")} and {@code IllegalStateException("not built until T17")} (the module has to compile
 * between the tasks, and the exception text must not be the one that this scan forbids). T12
 * replaced the first; T17 replaced the second, and from then on the scan reports any main source
 * that holds the text "not built until T" as well.
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
      String text = Files.readString(file, StandardCharsets.UTF_8);
      if (text.contains("UnsupportedOperationException") || text.contains("not built until T")) {
        offenders.add(sources.relativize(file).toString().replace('\\', '/'));
      }
    }
    assertThat(offenders)
        .as(
            "main sources that still throw UnsupportedOperationException or hold a placeholder"
                + " ('not built until T')")
        .isEmpty();
  }
}
