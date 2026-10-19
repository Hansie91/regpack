package dev.regpack.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Review focus LF17, Convention 7: the PostgreSQL adapter's main code uses no wall clock,
 * randomness, default locale, default zone or default charset. Time comes from an injected {@code
 * Clock}; the only SQL default of the schema is the migration history's {@code applied_at}.
 */
class NoWallClockRandomLocaleTest {
  @Test
  void postgresMainClassesAreClean() throws IOException {
    Path classes = Path.of(System.getProperty("regpack.classes.dir"));
    assertThat(Files.isDirectory(classes)).isTrue();
    assertThat(classes.resolve("dev/regpack/postgres/SqlIdentifier.class")).exists();
    assertThat(classes.resolve("dev/regpack/postgres/migrate/Migrator.class")).exists();
    assertThat(ForbiddenCallScan.scan(classes, EnumSet.allOf(RuleSet.class))).isEmpty();
  }
}
