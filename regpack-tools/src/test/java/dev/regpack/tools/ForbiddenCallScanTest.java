package dev.regpack.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.regpack.tools.ForbiddenCallScan.RuleSet;
import dev.regpack.tools.ForbiddenCallScan.Violation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForbiddenCallScanTest {
  @TempDir Path dir;

  private void compile(Path out, String className, String source) throws IOException {
    Path src = Files.createTempDirectory(dir, "src");
    Path file = src.resolve(className.substring(className.lastIndexOf('.') + 1) + ".java");
    Files.writeString(file, source, StandardCharsets.UTF_8);
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc = compiler.run(null, null, null, "-d", out.toString(), file.toString());
    if (rc != 0) {
      fail("compilation failed for " + className);
    }
  }

  private List<Violation> scanOf(String className, String source, Set<RuleSet> sets)
      throws IOException {
    Path out = Files.createTempDirectory(dir, "classes");
    compile(out, className, source);
    return ForbiddenCallScan.scan(out, sets);
  }

  @Test
  void flagsInstantNow() throws IOException {
    var v =
        scanOf(
            "A",
            "class A { Object f() { return java.time.Instant.now(); } }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(v).containsExactly(new Violation("A", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }

  @Test
  void flagsMethodReferenceToWallClock() throws IOException {
    var v =
        scanOf(
            "B",
            "class B { java.util.function.Supplier<Object> s = java.time.Instant::now; }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(v).containsExactly(new Violation("B", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }

  @Test
  void flagsDefaultLocaleToLowerCase() throws IOException {
    var bad =
        scanOf(
            "C",
            "class C { String f(String s) { return s.toLowerCase(); } }",
            Set.of(RuleSet.LOCALE));
    assertThat(bad)
        .containsExactly(new Violation("C", "java/lang/String.toLowerCase", RuleSet.LOCALE));
    var good =
        scanOf(
            "D",
            "class D { String f(String s) { return s.toLowerCase(java.util.Locale.ROOT); } }",
            Set.of(RuleSet.LOCALE));
    assertThat(good).isEmpty();
  }

  @Test
  void acceptsExplicitLocaleFormat() throws IOException {
    var good =
        scanOf(
            "E",
            "class E { String f() { return String.format(java.util.Locale.ROOT, \"%d\", 1); } }",
            Set.of(RuleSet.LOCALE));
    assertThat(good).isEmpty();
    var bad =
        scanOf(
            "E2",
            "class E2 { String f() { return String.format(\"%d\", 1); } }",
            Set.of(RuleSet.LOCALE));
    assertThat(bad).containsExactly(new Violation("E2", "java/lang/String.format", RuleSet.LOCALE));
  }

  @Test
  void flagsRandomness() throws IOException {
    var v =
        scanOf(
            "F",
            "class F { Object a = new java.util.Random(); Object b = java.util.UUID.randomUUID(); }",
            Set.of(RuleSet.RANDOMNESS));
    assertThat(v)
        .containsExactly(
            new Violation("F", "java/util/Random.<init>", RuleSet.RANDOMNESS),
            new Violation("F", "java/util/UUID.randomUUID", RuleSet.RANDOMNESS));
  }

  @Test
  void ruleSetsAreIndependent() throws IOException {
    var v =
        scanOf(
            "A",
            "class A { Object f() { return java.time.Instant.now(); } }",
            Set.of(RuleSet.LOCALE, RuleSet.RANDOMNESS));
    assertThat(v).isEmpty();
  }

  @Test
  void cleanClassHasNoViolationsAndNestedDirectoriesAreScanned() throws IOException {
    var clean =
        scanOf(
            "G",
            "package p.q; public class G { int f() { return 1; } }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(clean).isEmpty();
    var dirty =
        scanOf(
            "G",
            "package p.q; public class G { Object f() { return java.time.Instant.now(); } }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(dirty)
        .containsExactly(new Violation("p/q/G", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }
}
