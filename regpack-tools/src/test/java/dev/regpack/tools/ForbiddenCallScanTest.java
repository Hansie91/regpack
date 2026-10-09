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
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

  @Test
  void duplicateCallIsReportedOnce() throws IOException {
    // two overloads are two constant pool entries that map to the same violation
    var v =
        scanOf(
            "H",
            "class H { Object f(java.time.Clock c) {"
                + " java.time.Instant.now(); return java.time.Instant.now(c); } }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(v).containsExactly(new Violation("H", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }

  @Test
  void violationsAreSortedEvenWhenConstantPoolOrderDiffers() throws IOException {
    // the pool lists UUID.randomUUID before Random.<init>, the sorted order is the reverse
    var v =
        scanOf(
            "I",
            "class I { Object a = java.util.UUID.randomUUID(); Object b = new java.util.Random(); }",
            Set.of(RuleSet.RANDOMNESS));
    assertThat(v)
        .containsExactly(
            new Violation("I", "java/util/Random.<init>", RuleSet.RANDOMNESS),
            new Violation("I", "java/util/UUID.randomUUID", RuleSet.RANDOMNESS));
  }

  @Test
  void violationsAreOrderedByClassNameAcrossClasses() throws IOException {
    Path out = Files.createTempDirectory(dir, "classes");
    compile(out, "Zeta", "class Zeta { Object f() { return java.time.Instant.now(); } }");
    compile(out, "Alpha", "class Alpha { Object f() { return java.time.Instant.now(); } }");
    var v = ForbiddenCallScan.scan(out, Set.of(RuleSet.WALL_CLOCK));
    assertThat(v)
        .containsExactly(
            new Violation("Alpha", "java/time/Instant.now", RuleSet.WALL_CLOCK),
            new Violation("Zeta", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }

  @Test
  void classNameOrderIsNotFileOrder() throws IOException {
    // Z$1.class sorts before Z.class as a file name, but Z sorts before Z$1 as a class name
    var v =
        scanOf(
            "Z",
            "class Z { Object f() { return new Object() {"
                + " Object g() { return java.time.Instant.now(); } }; }"
                + " Object h() { return java.time.Instant.now(); } }",
            Set.of(RuleSet.WALL_CLOCK));
    assertThat(v)
        .containsExactly(
            new Violation("Z", "java/time/Instant.now", RuleSet.WALL_CLOCK),
            new Violation("Z$1", "java/time/Instant.now", RuleSet.WALL_CLOCK));
  }

  @Test
  void flagsInterfaceMethodReferenceOwners() throws IOException {
    var direct =
        scanOf(
            "J",
            "class J { Object f() { return java.util.random.RandomGenerator.getDefault(); } }",
            Set.of(RuleSet.RANDOMNESS));
    assertThat(direct)
        .containsExactly(
            new Violation("J", "java/util/random/RandomGenerator.getDefault", RuleSet.RANDOMNESS));
    var reference =
        scanOf(
            "K",
            "class K { java.util.function.Supplier<Object> s ="
                + " java.util.random.RandomGenerator::getDefault; }",
            Set.of(RuleSet.RANDOMNESS));
    assertThat(reference)
        .containsExactly(
            new Violation("K", "java/util/random/RandomGenerator.getDefault", RuleSet.RANDOMNESS));
  }

  private static Stream<Arguments> forbiddenProbes() {
    return Stream.of(
        Arguments.of("Thread.sleep(1L);", "java/lang/Thread.sleep", RuleSet.WALL_CLOCK),
        Arguments.of(
            "java.time.Clock.systemUTC();", "java/time/Clock.systemUTC", RuleSet.WALL_CLOCK),
        Arguments.of(
            "java.time.Clock.systemDefaultZone();",
            "java/time/Clock.systemDefaultZone",
            RuleSet.WALL_CLOCK),
        Arguments.of(
            "java.time.Clock.system(java.time.ZoneOffset.UTC);",
            "java/time/Clock.system",
            RuleSet.WALL_CLOCK),
        Arguments.of("java.time.LocalDate.now();", "java/time/LocalDate.now", RuleSet.WALL_CLOCK),
        Arguments.of(
            "System.currentTimeMillis();",
            "java/lang/System.currentTimeMillis",
            RuleSet.WALL_CLOCK),
        Arguments.of("System.nanoTime();", "java/lang/System.nanoTime", RuleSet.WALL_CLOCK),
        Arguments.of("new java.util.Date();", "java/util/Date.<init>", RuleSet.WALL_CLOCK),
        Arguments.of(
            "java.util.Calendar.getInstance();",
            "java/util/Calendar.getInstance",
            RuleSet.WALL_CLOCK),
        Arguments.of(
            "new java.security.SecureRandom();",
            "java/security/SecureRandom.<init>",
            RuleSet.RANDOMNESS),
        Arguments.of(
            "new java.util.SplittableRandom();",
            "java/util/SplittableRandom.<init>",
            RuleSet.RANDOMNESS),
        Arguments.of(
            "java.util.concurrent.ThreadLocalRandom.current();",
            "java/util/concurrent/ThreadLocalRandom.current",
            RuleSet.RANDOMNESS),
        Arguments.of("Math.random();", "java/lang/Math.random", RuleSet.RANDOMNESS),
        Arguments.of(
            "java.util.random.RandomGenerator.of(\"L64X128MixRandom\");",
            "java/util/random/RandomGenerator.of",
            RuleSet.RANDOMNESS),
        Arguments.of("\"a\".toUpperCase();", "java/lang/String.toUpperCase", RuleSet.LOCALE),
        Arguments.of("\"a\".formatted(1);", "java/lang/String.formatted", RuleSet.LOCALE),
        Arguments.of(
            "java.util.Locale.getDefault();", "java/util/Locale.getDefault", RuleSet.LOCALE),
        Arguments.of("new java.util.Formatter();", "java/util/Formatter.<init>", RuleSet.LOCALE),
        Arguments.of("System.out.printf(\"x\");", "java/io/PrintStream.printf", RuleSet.LOCALE),
        Arguments.of("System.out.format(\"x\");", "java/io/PrintStream.format", RuleSet.LOCALE),
        Arguments.of(
            "java.time.format.DateTimeFormatter.ofPattern(\"yyyy\");",
            "java/time/format/DateTimeFormatter.ofPattern",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.NumberFormat.getInstance();",
            "java/text/NumberFormat.getInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "new java.text.DecimalFormat();", "java/text/DecimalFormat.<init>", RuleSet.LOCALE),
        Arguments.of(
            "new java.text.SimpleDateFormat();",
            "java/text/SimpleDateFormat.<init>",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.Collator.getInstance();", "java/text/Collator.getInstance", RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getInstance();",
            "java/text/DateFormat.getInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getDateInstance();",
            "java/text/DateFormat.getDateInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);",
            "java/text/DateFormat.getDateInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getTimeInstance();",
            "java/text/DateFormat.getTimeInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);",
            "java/text/DateFormat.getTimeInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getDateTimeInstance();",
            "java/text/DateFormat.getDateTimeInstance",
            RuleSet.LOCALE),
        Arguments.of(
            "java.text.DateFormat.getDateTimeInstance("
                + "java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);",
            "java/text/DateFormat.getDateTimeInstance",
            RuleSet.LOCALE));
  }

  @ParameterizedTest
  @MethodSource("forbiddenProbes")
  void flagsEveryRuleRow(String statement, String reference, RuleSet ruleSet) throws IOException {
    var v =
        scanOf(
            "P",
            "class P { void f() throws Exception { " + statement + " } }",
            Set.of(RuleSet.values()));
    assertThat(v).containsExactly(new Violation("P", reference, ruleSet));
  }

  private static Stream<String> cleanProbes() {
    return Stream.of(
        "java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);",
        "java.time.LocalDate.of(2020, 1, 2);",
        "new java.util.Date(5L);",
        "\"a\".toUpperCase(java.util.Locale.ROOT);",
        "\"a\".toLowerCase(java.util.Locale.ROOT);",
        "String.format(java.util.Locale.ROOT, \"%d\", 1);",
        "new java.util.Formatter(java.util.Locale.ROOT);",
        "System.out.printf(java.util.Locale.ROOT, \"x\");",
        "System.out.format(java.util.Locale.ROOT, \"x\");",
        "java.time.format.DateTimeFormatter.ofPattern(\"yyyy\", java.util.Locale.ROOT);",
        "java.text.NumberFormat.getInstance(java.util.Locale.ROOT);",
        "java.text.NumberFormat.getCurrencyInstance(java.util.Locale.ROOT);",
        "java.text.Collator.getInstance(java.util.Locale.ROOT);",
        "java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, java.util.Locale.ROOT);",
        "java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.ROOT);",
        "java.text.DateFormat.getDateTimeInstance("
            + "java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, java.util.Locale.ROOT);");
  }

  @ParameterizedTest
  @MethodSource("cleanProbes")
  void acceptsCleanCounterparts(String statement) throws IOException {
    var v =
        scanOf(
            "Q",
            "class Q { void f() throws Exception { " + statement + " } }",
            Set.of(RuleSet.values()));
    assertThat(v).isEmpty();
  }
}
