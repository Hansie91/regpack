package dev.regpack.pack.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The registry of named human golden-case reviewers, maintained by the owner (spec 14.2 O1 and O2).
 * A reviewer id typed into a case file counts only if it is listed here.
 */
public final class ReviewerRegistry {
  /** One registered person. */
  public record Reviewer(String id, String fullName, String role) {}

  private final Map<String, Reviewer> byId;

  private ReviewerRegistry(Map<String, Reviewer> byId) {
    this.byId = Map.copyOf(byId);
  }

  /**
   * Parses lines {@code id|Full name|role}. Blank lines and lines starting with {@code #} are
   * ignored; a malformed line or a duplicate id is rejected.
   */
  public static ReviewerRegistry parse(String text) {
    Map<String, Reviewer> reviewers = new LinkedHashMap<>();
    String[] lines = text.split("\r?\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\|", -1);
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "line " + (i + 1) + ": expected 'id|Full name|role' but found: " + line);
      }
      String id = parts[0].strip();
      String fullName = parts[1].strip();
      String role = parts[2].strip();
      if (id.isEmpty() || fullName.isEmpty() || role.isEmpty()) {
        throw new IllegalArgumentException("line " + (i + 1) + ": id, name and role are required");
      }
      if (reviewers.put(id, new Reviewer(id, fullName, role)) != null) {
        throw new IllegalArgumentException("line " + (i + 1) + ": duplicate reviewer id " + id);
      }
    }
    return new ReviewerRegistry(reviewers);
  }

  /** Reads and parses a registry file (UTF-8). */
  public static ReviewerRegistry load(Path file) {
    try {
      return parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read reviewer registry " + file, e);
    }
  }

  public boolean contains(String reviewerId) {
    return byId.containsKey(reviewerId);
  }

  /** The number of registered reviewers (the {@code pack test} audit line prints it). */
  public int size() {
    return byId.size();
  }
}
