package dev.regpack.engine.expr;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The four switches of the DECIDE vectors: points where the specification is silent and the layers
 * under test differ (assumption A2, open item OI-5). The provisional values are the in-house
 * outcomes. A layer reads its rulings once, at construction, into a final field; there is no static
 * state, so two layers with different rulings can coexist.
 *
 * @param intDecimalMix D13: {@code 1 + dec('0.5')}
 * @param andLeftError A04: {@code error && false}
 * @param orLeftError E06: {@code error || true}
 * @param hasBelowAbsent A07: {@code has(a.b.c)} when {@code a.b} is declared and unbound
 */
public record Rulings(
    IntDecimalMix intDecimalMix,
    AndLeftError andLeftError,
    OrLeftError orLeftError,
    HasBelowAbsent hasBelowAbsent) {

  /** D13: arithmetic that mixes an int with a decimal. */
  public enum IntDecimalMix {
    /** The int is widened to a decimal and the result is a decimal. */
    ALLOW,
    /** The mix is a TYPE error (comparison and equality stay numeric). */
    DENY
  }

  /** A04: an error raised by the left operand of {@code &&}. */
  public enum AndLeftError {
    /** The error propagates whatever the right operand is. */
    PROPAGATE,
    /** The right operand is evaluated; if it is false the result is false, else the error stays. */
    ABSORB
  }

  /** E06: an error raised by the left operand of {@code ||}. */
  public enum OrLeftError {
    /** The error propagates whatever the right operand is. */
    PROPAGATE,
    /** The right operand is evaluated; if it is true the result is true, else the error stays. */
    ABSORB
  }

  /** A07: {@code has(p)} when a declared strict ancestor path of {@code p} is unbound. */
  public enum HasBelowAbsent {
    /** The answer is simply whether {@code p} is bound. */
    FALSE,
    /** {@code has(p)} raises ABSENT when a declared ancestor path of {@code p} is unbound. */
    ERROR_IF_ANCESTOR_ABSENT
  }

  public Rulings {
    Objects.requireNonNull(intDecimalMix, "intDecimalMix");
    Objects.requireNonNull(andLeftError, "andLeftError");
    Objects.requireNonNull(orLeftError, "orLeftError");
    Objects.requireNonNull(hasBelowAbsent, "hasBelowAbsent");
  }

  /** The in-house outcomes: ALLOW, PROPAGATE, PROPAGATE, FALSE. */
  public static Rulings provisional() {
    return new Rulings(
        IntDecimalMix.ALLOW, AndLeftError.PROPAGATE, OrLeftError.PROPAGATE, HasBelowAbsent.FALSE);
  }

  /** Name of the one rulings file, a resource next to this class. */
  public static final String RESOURCE = "rulings.tsv";

  private static final List<String> HEADER =
      List.of(
          "id",
          "status",
          "switch",
          "value",
          "alt_value",
          "expected",
          "expected_alt",
          "ruled_by",
          "ruled_on",
          "note");

  /** The five rows of the file: vector id, switch name, in-house value, alternative ("-": none). */
  private record Spec(String id, String switchName, String inHouse, String alt) {}

  private static final List<Spec> SPECS =
      List.of(
          new Spec("D13", "intDecimalMix", "ALLOW", "DENY"),
          new Spec("A04", "andLeftError", "PROPAGATE", "ABSORB"),
          new Spec("E06", "orLeftError", "PROPAGATE", "ABSORB"),
          new Spec("A07", "hasBelowAbsent", "FALSE", "ERROR_IF_ANCESTOR_ABSENT"),
          // S07 is validated, not a switch: enum with string equality is by name until the grammar
          // has an enum literal
          new Spec("S07", "enumStringEquality", "BY_NAME", "-"));

  /**
   * Reads the rulings from the committed file {@value #RESOURCE}. The four switches are built from
   * the {@code value} cells; a ruling is the change of one such cell.
   *
   * @throws IllegalStateException if the resource is missing
   * @throws IllegalArgumentException if the file is malformed
   */
  public static Rulings load() {
    try (InputStream in = Rulings.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("missing resource " + RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses the text of a rulings file: UTF-8, tab-separated, lines starting with {@code #} are
   * comments, the first other line is the header, then exactly one row per known id (D13, A04, E06,
   * A07, S07). CRLF is accepted. A row with status {@code PROVISIONAL} has {@code -} as ruled_by
   * and ruled_on, a row with status {@code RULED} has both, ruled_on being a date. Every violation
   * is an {@link IllegalArgumentException} that names the row.
   */
  public static Rulings parse(String text) {
    Objects.requireNonNull(text, "text");
    boolean headerSeen = false;
    Set<String> seen = new HashSet<>();
    IntDecimalMix mix = null;
    AndLeftError and = null;
    OrLeftError or = null;
    HasBelowAbsent has = null;
    String[] lines = text.replace("\r\n", "\n").split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (i == lines.length - 1 && line.isEmpty()) {
        continue;
      }
      if (line.startsWith("#")) {
        continue;
      }
      String[] cells = line.split("\t", -1);
      if (!headerSeen) {
        if (!List.of(cells).equals(HEADER)) {
          throw new IllegalArgumentException("header must be: " + String.join(" ", HEADER));
        }
        headerSeen = true;
        continue;
      }
      String label = shown(cells[0]);
      if (cells.length != HEADER.size()) {
        throw new IllegalArgumentException(
            "row " + label + ": need " + HEADER.size() + " cells, found " + cells.length);
      }
      Spec spec = specOf(cells[0]);
      if (spec == null) {
        throw new IllegalArgumentException("row " + label + ": unknown id");
      }
      if (!seen.add(spec.id())) {
        throw new IllegalArgumentException("duplicate row " + spec.id());
      }
      checkRow(spec, cells);
      switch (spec.id()) {
        case "D13" -> mix = IntDecimalMix.valueOf(cells[3]);
        case "A04" -> and = AndLeftError.valueOf(cells[3]);
        case "E06" -> or = OrLeftError.valueOf(cells[3]);
        case "A07" -> has = HasBelowAbsent.valueOf(cells[3]);
        default -> {
          // S07: validated only
        }
      }
    }
    if (!headerSeen) {
      throw new IllegalArgumentException("header missing: " + String.join(" ", HEADER));
    }
    for (Spec spec : SPECS) {
      if (!seen.contains(spec.id())) {
        throw new IllegalArgumentException("missing row " + spec.id());
      }
    }
    return new Rulings(mix, and, or, has);
  }

  private static Spec specOf(String id) {
    for (Spec spec : SPECS) {
      if (spec.id().equals(id)) {
        return spec;
      }
    }
    return null;
  }

  private static void checkRow(Spec spec, String[] c) {
    String row = "row " + spec.id() + ": ";
    String status = c[1];
    if (!status.equals("PROVISIONAL") && !status.equals("RULED")) {
      throw new IllegalArgumentException(row + "status must be PROVISIONAL or RULED");
    }
    if (!c[2].equals(spec.switchName())) {
      throw new IllegalArgumentException(row + "switch must be " + spec.switchName());
    }
    boolean hasAlt = !spec.alt().equals("-");
    if (!c[3].equals(spec.inHouse()) && !(hasAlt && c[3].equals(spec.alt()))) {
      throw new IllegalArgumentException(
          row
              + "value must be "
              + spec.inHouse()
              + (hasAlt ? " or " + spec.alt() : " (S07 has no alternative)"));
    }
    if (!c[4].equals(spec.alt())) {
      throw new IllegalArgumentException(row + "alt_value must be " + spec.alt());
    }
    if (c[5].isEmpty() || c[6].isEmpty()) {
      throw new IllegalArgumentException(row + "expected and expected_alt must not be empty");
    }
    boolean noMeta = c[7].equals("-") && c[8].equals("-");
    if (status.equals("PROVISIONAL") && !noMeta) {
      throw new IllegalArgumentException(row + "a PROVISIONAL row has - as ruled_by and ruled_on");
    }
    if (status.equals("RULED")) {
      if (c[7].isEmpty() || c[7].equals("-") || c[8].equals("-")) {
        throw new IllegalArgumentException(row + "a RULED row needs ruled_by and ruled_on");
      }
      if (!c[8].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
        throw new IllegalArgumentException(row + "ruled_on must be a date yyyy-mm-dd");
      }
      try {
        LocalDate.parse(c[8]);
      } catch (DateTimeParseException e) {
        throw new IllegalArgumentException(row + "ruled_on must be a date yyyy-mm-dd", e);
      }
    }
  }

  /** Untrusted text bounded and reduced to printable ASCII before it is echoed. */
  private static String shown(String text) {
    StringBuilder out = new StringBuilder();
    int limit = Math.min(text.length(), 24);
    for (int i = 0; i < limit; i++) {
      char c = text.charAt(i);
      out.append(c >= 0x20 && c < 0x7f ? c : '?');
    }
    if (text.length() > limit) {
      out.append("...");
    }
    return out.toString();
  }
}
