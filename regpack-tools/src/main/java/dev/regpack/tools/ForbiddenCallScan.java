package dev.regpack.tools;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Scans compiled classes for references to wall-clock, randomness and default-locale APIs. The scan
 * reads the constant pool, so method references and other indirect uses are found as well as
 * ordinary calls.
 */
public final class ForbiddenCallScan {
  /** The three independent rule sets of the determinism rules. */
  public enum RuleSet {
    WALL_CLOCK,
    RANDOMNESS,
    LOCALE,
    /**
     * Platform-dependent defaults other than the locale: the default time zone, the line separator
     * and the default charset, as the no-charset forms of the text conversions.
     */
    PLATFORM_DEFAULT
  }

  /** One forbidden reference found in one class. */
  public record Violation(String className, String reference, RuleSet ruleSet) {}

  /** An empty name or descriptor matches anything; an owner ending in '/' is a package prefix. */
  private record Rule(RuleSet ruleSet, String owner, String name, String descriptor) {
    boolean matches(String refOwner, String refName, String refDescriptor) {
      boolean ownerOk = owner.endsWith("/") ? refOwner.startsWith(owner) : refOwner.equals(owner);
      return ownerOk
          && (name.isEmpty() || name.equals(refName))
          && (descriptor.isEmpty() || descriptor.equals(refDescriptor));
    }
  }

  private static final String STR = "Ljava/lang/String;";
  private static final String OBJS = "[Ljava/lang/Object;";

  private static final List<Rule> RULES = rules();

  private ForbiddenCallScan() {}

  private static List<Rule> rules() {
    List<Rule> r = new ArrayList<>();
    // WALL_CLOCK
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/time/", "now", ""));
    for (String n : List.of("systemUTC", "systemDefaultZone", "system")) {
      r.add(new Rule(RuleSet.WALL_CLOCK, "java/time/Clock", n, ""));
    }
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/lang/System", "currentTimeMillis", ""));
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/lang/System", "nanoTime", ""));
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/lang/Thread", "sleep", ""));
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/util/Date", "<init>", "()V"));
    r.add(new Rule(RuleSet.WALL_CLOCK, "java/util/Calendar", "getInstance", ""));
    // RANDOMNESS
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/Random", "<init>", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/SplittableRandom", "<init>", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/security/SecureRandom", "<init>", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/concurrent/ThreadLocalRandom", "current", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/lang/Math", "random", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/UUID", "randomUUID", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/random/RandomGenerator", "of", ""));
    r.add(new Rule(RuleSet.RANDOMNESS, "java/util/random/RandomGenerator", "getDefault", ""));
    // LOCALE: overloads without a Locale
    r.add(new Rule(RuleSet.LOCALE, "java/lang/String", "toLowerCase", "()" + STR));
    r.add(new Rule(RuleSet.LOCALE, "java/lang/String", "toUpperCase", "()" + STR));
    r.add(new Rule(RuleSet.LOCALE, "java/lang/String", "format", "(" + STR + OBJS + ")" + STR));
    r.add(new Rule(RuleSet.LOCALE, "java/lang/String", "formatted", "(" + OBJS + ")" + STR));
    r.add(new Rule(RuleSet.LOCALE, "java/util/Locale", "getDefault", ""));
    for (String n :
        List.of(
            "getInstance",
            "getNumberInstance",
            "getIntegerInstance",
            "getCurrencyInstance",
            "getPercentInstance",
            "getCompactNumberInstance")) {
      r.add(new Rule(RuleSet.LOCALE, "java/text/NumberFormat", n, "()Ljava/text/NumberFormat;"));
    }
    // DateFormat factories without a Locale parameter (the style-only overloads use the default)
    String returnsDateFormat = ")Ljava/text/DateFormat;";
    r.add(new Rule(RuleSet.LOCALE, "java/text/DateFormat", "getInstance", "(" + returnsDateFormat));
    for (String n : List.of("getDateInstance", "getTimeInstance")) {
      r.add(new Rule(RuleSet.LOCALE, "java/text/DateFormat", n, "(" + returnsDateFormat));
      r.add(new Rule(RuleSet.LOCALE, "java/text/DateFormat", n, "(I" + returnsDateFormat));
    }
    r.add(
        new Rule(
            RuleSet.LOCALE,
            "java/text/DateFormat",
            "getDateTimeInstance",
            "(" + returnsDateFormat));
    r.add(
        new Rule(
            RuleSet.LOCALE,
            "java/text/DateFormat",
            "getDateTimeInstance",
            "(II" + returnsDateFormat));
    r.add(new Rule(RuleSet.LOCALE, "java/text/DecimalFormat", "<init>", ""));
    r.add(new Rule(RuleSet.LOCALE, "java/text/SimpleDateFormat", "<init>", ""));
    r.add(new Rule(RuleSet.LOCALE, "java/text/Collator", "getInstance", "()Ljava/text/Collator;"));
    r.add(
        new Rule(
            RuleSet.LOCALE,
            "java/time/format/DateTimeFormatter",
            "ofPattern",
            "(" + STR + ")Ljava/time/format/DateTimeFormatter;"));
    String printStreamDescriptor = "(" + STR + OBJS + ")Ljava/io/PrintStream;";
    r.add(new Rule(RuleSet.LOCALE, "java/io/PrintStream", "printf", printStreamDescriptor));
    r.add(new Rule(RuleSet.LOCALE, "java/io/PrintStream", "format", printStreamDescriptor));
    r.add(new Rule(RuleSet.LOCALE, "java/util/Formatter", "<init>", "()V"));
    // LOCALE: formats and scanners that take the default locale, and the localized styles
    r.add(new Rule(RuleSet.LOCALE, "java/text/MessageFormat", "<init>", "(" + STR + ")V"));
    r.add(
        new Rule(
            RuleSet.LOCALE, "java/text/MessageFormat", "format", "(" + STR + OBJS + ")" + STR));
    r.add(new Rule(RuleSet.LOCALE, "java/util/Scanner", "<init>", ""));
    for (String n : List.of("ofLocalizedDate", "ofLocalizedTime", "ofLocalizedDateTime")) {
      r.add(new Rule(RuleSet.LOCALE, "java/time/format/DateTimeFormatter", n, ""));
    }
    // PLATFORM_DEFAULT: time zone, line separator and charset of the platform
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/time/ZoneId", "systemDefault", ""));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/util/TimeZone", "getDefault", ""));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/util/TimeZone", "setDefault", ""));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/lang/System", "lineSeparator", ""));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/nio/charset/Charset", "defaultCharset", ""));
    // the forms of text conversion that use the default charset
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/lang/String", "getBytes", "()[B"));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/lang/String", "<init>", "([B)V"));
    r.add(new Rule(RuleSet.PLATFORM_DEFAULT, "java/lang/String", "<init>", "([BII)V"));
    r.add(
        new Rule(
            RuleSet.PLATFORM_DEFAULT, "java/io/ByteArrayOutputStream", "toString", "()" + STR));
    r.add(
        new Rule(
            RuleSet.PLATFORM_DEFAULT,
            "java/io/InputStreamReader",
            "<init>",
            "(Ljava/io/InputStream;)V"));
    r.add(
        new Rule(
            RuleSet.PLATFORM_DEFAULT,
            "java/io/OutputStreamWriter",
            "<init>",
            "(Ljava/io/OutputStream;)V"));
    return List.copyOf(r);
  }

  /**
   * Scans every {@code *.class} file below {@code classesDir} for references of the requested rule
   * sets.
   *
   * @return the violations, sorted by class name and reference, without duplicates
   */
  public static List<Violation> scan(Path classesDir, Set<RuleSet> ruleSets) throws IOException {
    Comparator<Violation> order =
        Comparator.comparing(Violation::className)
            .thenComparing(Violation::reference)
            .thenComparing(Violation::ruleSet);
    Set<Violation> found = new TreeSet<>(order);
    List<Path> files;
    try (Stream<Path> walk = Files.walk(classesDir)) {
      files = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
    }
    for (Path file : files) {
      ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
      String className = model.thisClass().asInternalName();
      for (PoolEntry entry : model.constantPool()) {
        if (!(entry instanceof MethodRefEntry || entry instanceof InterfaceMethodRefEntry)) {
          continue;
        }
        MemberRefEntry ref = (MemberRefEntry) entry;
        String owner = ref.owner().asInternalName();
        String name = ref.name().stringValue();
        String descriptor = ref.type().stringValue();
        for (Rule rule : RULES) {
          if (ruleSets.contains(rule.ruleSet()) && rule.matches(owner, name, descriptor)) {
            found.add(new Violation(className, owner + "." + name, rule.ruleSet()));
          }
        }
      }
    }
    return List.copyOf(found);
  }
}
