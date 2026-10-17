package dev.regpack.pack.source;

import dev.regpack.model.Sha256;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.check.ProblemSink;
import dev.regpack.pack.yaml.PackSyntaxException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code CHECKSUMS} file: one line {@code <64 lowercase hex> <path>} (two spaces) per file,
 * sorted by path, over the bytes with CRLF read as LF, so a Windows checkout verifies. It does not
 * list {@code CHECKSUMS} or {@code ATTESTATION}. It is an integrity check against accidental
 * change, not authenticity (plan hand-off to P9b).
 *
 * <p>Problem codes of {@link #verify}: {@code CHECKSUMS_MISSING}, {@code CHECKSUMS_MALFORMED},
 * {@code CHECKSUMS_DUPLICATE_ENTRY}, {@code CHECKSUM_MISMATCH}, {@code CHECKSUM_FILE_MISSING},
 * {@code CHECKSUM_UNLISTED_FILE}.
 */
public final class Checksums {
  private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  ([^ ]+)");

  private Checksums() {}

  /** The CHECKSUMS text of a source: sorted, LF line ends, not listing CHECKSUMS or ATTESTATION. */
  public static String render(PackSource source) {
    StringBuilder out = new StringBuilder();
    for (String path : source.paths()) {
      if (isReserved(path)) {
        continue;
      }
      out.append(hash(source.bytes(path))).append("  ").append(path).append('\n');
    }
    return out.toString();
  }

  /** Compares the CHECKSUMS file of a source with its files; the result is sorted. */
  public static List<PackProblem> verify(PackSource source) {
    ProblemSink problems = ProblemSink.forLoad();
    if (!source.has(PackSource.CHECKSUMS)) {
      problems.add(
          new PackProblem(
              "CHECKSUMS_MISSING", PackSource.CHECKSUMS, 0, "the pack has no CHECKSUMS"));
      return problems.sorted(PackSource.CHECKSUMS);
    }
    String text;
    try {
      text = source.text(PackSource.CHECKSUMS);
    } catch (PackSyntaxException e) {
      problems.add(new PackProblem(e.code(), PackSource.CHECKSUMS, 0, "CHECKSUMS is not text"));
      return problems.sorted(PackSource.CHECKSUMS);
    }
    Map<String, String> listed = new HashMap<>();
    int lineNo = 0;
    for (String line : lines(text)) {
      if (problems.truncated()) {
        break;
      }
      lineNo++;
      Matcher m = LINE.matcher(line);
      if (!m.matches() || !validPath(m.group(2)) || isReserved(m.group(2))) {
        problems.add(
            new PackProblem(
                "CHECKSUMS_MALFORMED",
                PackSource.CHECKSUMS,
                lineNo,
                "not '<64 hex>  <path>': " + PackProblem.excerpt(line)));
        continue;
      }
      String path = m.group(2);
      if (listed.putIfAbsent(path, m.group(1)) != null) {
        problems.add(
            new PackProblem(
                "CHECKSUMS_DUPLICATE_ENTRY",
                PackSource.CHECKSUMS,
                lineNo,
                "listed twice: " + PackProblem.excerpt(path)));
      }
    }
    for (Map.Entry<String, String> entry : listed.entrySet()) {
      String path = entry.getKey();
      if (!source.has(path)) {
        problems.add(
            new PackProblem(
                "CHECKSUM_FILE_MISSING", path, 0, "listed in CHECKSUMS, not in the pack"));
      } else if (!hash(source.bytes(path)).equals(entry.getValue())) {
        problems.add(
            new PackProblem("CHECKSUM_MISMATCH", path, 0, "the content differs from CHECKSUMS"));
      }
    }
    for (String path : source.paths()) {
      if (!isReserved(path) && !listed.containsKey(path)) {
        problems.add(
            new PackProblem(
                "CHECKSUM_UNLISTED_FILE", path, 0, "the file is not listed in CHECKSUMS"));
      }
    }
    return problems.sorted(PackSource.CHECKSUMS);
  }

  /**
   * The paths of the well-formed lines of a CHECKSUMS text (CRLF already read as LF), in file
   * order; malformed lines are skipped (verify reports them). Names are not validated here.
   */
  static List<String> listedPaths(String text) {
    List<String> paths = new ArrayList<>();
    for (String line : lines(text)) {
      Matcher m = LINE.matcher(line);
      if (m.matches() && !isReserved(m.group(2)) && !paths.contains(m.group(2))) {
        paths.add(m.group(2));
      }
    }
    return paths;
  }

  private static List<String> lines(String text) {
    List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1); // the final line end
    }
    return lines;
  }

  private static boolean validPath(String path) {
    try {
      PackSource.checkPath(path);
      return true;
    } catch (PackSyntaxException e) {
      return false;
    }
  }

  private static boolean isReserved(String path) {
    return path.equals(PackSource.CHECKSUMS) || path.equals(PackSource.ATTESTATION);
  }

  private static String hash(byte[] bytes) {
    return Sha256.of(lfNormalised(bytes)).hex();
  }

  /** The bytes with every CR LF pair replaced by LF (a lone CR stays). */
  static byte[] lfNormalised(byte[] bytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
        continue;
      }
      out.write(bytes[i]);
    }
    return out.toByteArray();
  }
}
