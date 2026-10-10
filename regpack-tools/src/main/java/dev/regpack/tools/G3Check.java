package dev.regpack.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate G3 tripwire (spec 17.1): no XSD is fetched and no XSD- or workbook-derived content enters
 * the repository before the owner clears G3. The only schemas allowed are the self-authored
 * synthetic ones of the SP-X spike. Prose under {@code docs/} may quote a namespace or a URL.
 *
 * <p>Problems are lines {@code CODE path}: {@code G3_NAMESPACE}, {@code G3_XSD_PATH}, {@code
 * G3_SYNTHETIC_MARKER}, {@code G3_SCHEMA_LOCATION}, {@code G3_WORKBOOK}, {@code G3_ARCHIVE}.
 */
public final class G3Check {
  /** The ISO 20022 schema namespace prefix, assembled so that this source never trips itself. */
  private static final String ISO_NAMESPACE = "urn:" + "iso:std:iso:" + "20022:tech:xsd";

  private static final String MARKER =
      "<!-- SELF-AUTHORED SYNTHETIC SCHEMA: not derived from any ESMA or ISO 20022 artefact"
          + " (gate G3) -->";
  private static final String SYNTHETIC_NAMESPACE_PREFIX = "urn:regpack:spike:synthetic:";
  private static final List<String> SYNTHETIC_DIRECTORIES =
      List.of(
          "spikes/spike-x/src/main/resources/synthetic/",
          "spikes/spike-x/src/test/resources/synthetic/");
  private static final List<String> FORBIDDEN_HOSTS =
      List.of("esma." + "europa.eu", "iso" + "20022.org");
  private static final Pattern SCHEMA_LOCATION =
      Pattern.compile("schemaLocation\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final Pattern TARGET_NAMESPACE =
      Pattern.compile("targetNamespace\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

  private G3Check() {}

  /**
   * Checks files of the repository.
   *
   * @param root the repository root
   * @param relativePaths the files to check, relative to the root; names are checked even when the
   *     file is gone, content only when it exists
   * @return the problems in input order, empty when clean
   */
  public static List<String> check(Path root, List<String> relativePaths) {
    List<String> problems = new ArrayList<>();
    for (String given : relativePaths) {
      String path = given.replace('\\', '/');
      String lower = path.toLowerCase(Locale.ROOT);
      String name = lower.substring(lower.lastIndexOf('/') + 1);
      boolean underDocs = path.startsWith("docs/");
      boolean xsd = lower.endsWith(".xsd");
      boolean synthetic = SYNTHETIC_DIRECTORIES.stream().anyMatch(path::startsWith);
      String text = null;
      Path file = root.resolve(path);
      if (Files.isRegularFile(file)) {
        try {
          text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
          problems.add("G3_UNREADABLE " + path);
        }
      }
      if (xsd && !synthetic) {
        problems.add("G3_XSD_PATH " + path);
      }
      if (xsd && synthetic && text != null && !isSelfAuthored(text)) {
        problems.add("G3_SYNTHETIC_MARKER " + path);
      }
      if (text != null && !underDocs) {
        if (text.contains(ISO_NAMESPACE)) {
          problems.add("G3_NAMESPACE " + path);
        }
        if (hasForbiddenSchemaLocation(text)) {
          problems.add("G3_SCHEMA_LOCATION " + path);
        }
      }
      if (lower.endsWith(".xlsx")
          || lower.endsWith(".xlsm")
          || lower.endsWith(".xls")
          || (name.contains("workbook") && !lower.endsWith(".md"))) {
        problems.add("G3_WORKBOOK " + path);
      }
      if (lower.endsWith(".zip")) {
        problems.add("G3_ARCHIVE " + path);
      }
    }
    return List.copyOf(problems);
  }

  private static boolean isSelfAuthored(String text) {
    boolean marker = text.lines().anyMatch(line -> line.strip().equals(MARKER));
    Matcher matcher = TARGET_NAMESPACE.matcher(text);
    boolean ownNamespace = false;
    if (matcher.find()) {
      String namespace = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      ownNamespace = namespace.startsWith(SYNTHETIC_NAMESPACE_PREFIX);
    }
    return marker && ownNamespace;
  }

  private static boolean hasForbiddenSchemaLocation(String text) {
    Matcher matcher = SCHEMA_LOCATION.matcher(text);
    while (matcher.find()) {
      String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
      String lower = value.toLowerCase(Locale.ROOT);
      if (FORBIDDEN_HOSTS.stream().anyMatch(lower::contains)) {
        return true;
      }
    }
    return false;
  }
}
