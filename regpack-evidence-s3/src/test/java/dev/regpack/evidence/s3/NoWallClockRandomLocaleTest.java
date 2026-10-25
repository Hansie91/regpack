package dev.regpack.evidence.s3;

import static org.assertj.core.api.Assertions.assertThat;

import dev.regpack.tools.ForbiddenCallScan;
import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/**
 * Determinism rules (Convention 8 of P0, Convention 4 of P3b1): the main code of the S3 module uses
 * no wall clock, randomness, default locale, default zone or default charset, and no sleep. Time
 * comes from the caller, waits are latches. The signer and the client are asserted to exist, so
 * that the scan is not vacuous.
 */
class NoWallClockRandomLocaleTest {
  @Test
  void s3MainClassesAreClean() throws IOException {
    Path classes = Path.of(System.getProperty("regpack.classes.dir"));
    assertThat(Files.isDirectory(classes)).isTrue();
    assertThat(classes.resolve("dev/regpack/evidence/s3/client/SigV4.class")).exists();
    assertThat(classes.resolve("dev/regpack/evidence/s3/client/S3Client.class")).exists();
    assertThat(ForbiddenCallScan.scan(classes, EnumSet.allOf(RuleSet.class))).isEmpty();
  }
}
