package dev.regpack.bench.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Platform Math functions may differ by one ulp between JVMs and CPUs; generators use StrictMath.
 */
class NoPlatformMathTest {
  private static final Pattern PLATFORM_MATH =
      Pattern.compile(
          "(?<![A-Za-z0-9_])Math\\s*\\.\\s*(pow|log\\w*|exp\\w*|sin\\w*|cos\\w*|tan\\w*|cbrt|hypot|atan\\w*|asin|acos)\\b");
  private static final Pattern STATIC_IMPORT =
      Pattern.compile("import\\s+static\\s+java\\.lang\\.Math\\b");

  static List<String> offences(String source) {
    List<String> found = new ArrayList<>();
    var matcher = PLATFORM_MATH.matcher(source);
    while (matcher.find()) {
      found.add(matcher.group());
    }
    if (STATIC_IMPORT.matcher(source).find()) {
      found.add("import static java.lang.Math");
    }
    return found;
  }

  @Test
  void detectorFlagsPlatformMathAndSparesStrictMath() {
    assertThat(offences("double x = Math.pow(2, 3);")).hasSize(1);
    assertThat(offences("double x = Math . log(2);")).hasSize(1);
    assertThat(offences("import static java.lang.Math.sin;")).isNotEmpty();
    assertThat(offences("double x = StrictMath.pow(2, 3) + StrictMath.log(2);")).isEmpty();
  }

  @Test
  void dataSourcesUseOnlyStrictMath() throws IOException {
    Path dir =
        Path.of(System.getProperty("regpack.repo.root"))
            .resolve("regpack-bench/src/main/java/dev/regpack/bench/data");
    List<Path> files;
    try (Stream<Path> walk = Files.walk(dir)) {
      files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    assertThat(files).isNotEmpty();
    for (Path file : files) {
      assertThat(offences(Files.readString(file, StandardCharsets.UTF_8)))
          .as(file.toString())
          .isEmpty();
    }
  }
}
