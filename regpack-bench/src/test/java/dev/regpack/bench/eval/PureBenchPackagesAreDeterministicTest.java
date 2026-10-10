package dev.regpack.bench.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Convention 5: the packages that decide or record a result use no wall clock, randomness or
 * default-locale API. The measuring packages ({@code proto}, {@code cli}) are not scanned.
 */
class PureBenchPackagesAreDeterministicTest {
  /** Packages that must exist now. */
  private static final List<String> REQUIRED = List.of("data", "stats", "eval");

  /** Packages of later tasks; scanned as soon as they exist. */
  private static final List<String> LATER = List.of("report", "imp", "consolidate");

  private static boolean hasClasses(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.anyMatch(p -> p.toString().endsWith(".class"));
    }
  }

  @Test
  void purePackagesHaveNoWallClockRandomnessOrDefaultLocaleReference() throws IOException {
    Path root = Path.of(System.getProperty("regpack.classes.dir")).resolve("dev/regpack/bench");
    List<String> scanned = new ArrayList<>();
    for (String pkg : REQUIRED) {
      assertThat(hasClasses(root.resolve(pkg))).as("package " + pkg + " has classes").isTrue();
      scanned.add(pkg);
    }
    for (String pkg : LATER) {
      if (hasClasses(root.resolve(pkg))) {
        scanned.add(pkg);
      }
    }
    for (String pkg : scanned) {
      assertThat(ForbiddenCallScan.scan(root.resolve(pkg), EnumSet.allOf(RuleSet.class)))
          .as("package " + pkg)
          .isEmpty();
    }
  }
}
