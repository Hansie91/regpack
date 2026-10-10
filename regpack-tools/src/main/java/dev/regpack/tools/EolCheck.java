package dev.regpack.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Line-ending, byte-order-mark and ASCII rules per file type. Unknown file types are ignored.
 * {@link #check} reads working-tree files, never index blobs.
 */
public final class EolCheck {
  private static final Set<String> CRLF_EXTENSIONS = Set.of(".cmd", ".bat");
  private static final Set<String> LF_EXTENSIONS =
      Set.of(
          ".sh",
          ".java",
          ".xml",
          ".md",
          ".yml",
          ".yaml",
          ".json",
          ".properties",
          ".service",
          ".tpl",
          ".txt",
          ".tsv",
          ".ps1",
          ".gitignore",
          ".gitattributes");
  private static final Set<String> LF_NAMES = Set.of("mvnw", "Dockerfile");
  private static final Set<String> ASCII_EXTENSIONS = Set.of(".sh", ".ps1", ".service");

  private EolCheck() {}

  /**
   * Checks the content of one file.
   *
   * @param path the path or name that selects the rules
   * @return the problems, empty when clean
   */
  public static List<String> checkBytes(String path, byte[] content) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    name = name.substring(name.lastIndexOf('\\') + 1);
    String extension = extensionOf(name.toLowerCase(Locale.ROOT));
    boolean crlf = CRLF_EXTENSIONS.contains(extension);
    boolean lf = LF_EXTENSIONS.contains(extension) || LF_NAMES.contains(name);
    if (!crlf && !lf) {
      return List.of();
    }
    List<String> problems = new ArrayList<>();
    if (content.length >= 3
        && content[0] == (byte) 0xEF
        && content[1] == (byte) 0xBB
        && content[2] == (byte) 0xBF) {
      problems.add(path + ": BOM");
    }
    if (crlf && hasBareLf(content)) {
      problems.add(path + ": LF_IN_CMD");
    }
    if (lf && contains(content, (byte) '\r')) {
      problems.add(path + ": CR_IN_LF_FILE");
    }
    if (ASCII_EXTENSIONS.contains(extension) && hasNonAscii(content)) {
      problems.add(path + ": NON_ASCII");
    }
    if ((extension.equals(".sh") || name.equals("mvnw"))
        && !(content.length >= 2 && content[0] == '#' && content[1] == '!')) {
      problems.add(path + ": NO_SHEBANG");
    }
    return List.copyOf(problems);
  }

  /**
   * Checks files of the working tree.
   *
   * @param root the repository root
   * @param relativePaths the files to check; a path that no longer exists is skipped
   * @return the problems, empty when clean
   */
  public static List<String> check(Path root, List<String> relativePaths) {
    List<String> problems = new ArrayList<>();
    for (String relative : relativePaths) {
      Path file = root.resolve(relative);
      if (!Files.isRegularFile(file)) {
        continue;
      }
      try {
        problems.addAll(checkBytes(relative, Files.readAllBytes(file)));
      } catch (IOException e) {
        problems.add(relative + ": cannot read: " + e.getMessage());
      }
    }
    return List.copyOf(problems);
  }

  /** The extension with its dot; a dot file such as {@code .gitignore} is its own extension. */
  private static String extensionOf(String lowerName) {
    int dot = lowerName.lastIndexOf('.');
    return dot < 0 ? "" : lowerName.substring(dot);
  }

  private static boolean hasBareLf(byte[] content) {
    for (int i = 0; i < content.length; i++) {
      if (content[i] == '\n' && (i == 0 || content[i - 1] != '\r')) {
        return true;
      }
    }
    return false;
  }

  private static boolean contains(byte[] content, byte value) {
    for (byte b : content) {
      if (b == value) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasNonAscii(byte[] content) {
    for (byte b : content) {
      if (b < 0) {
        return true;
      }
    }
    return false;
  }
}
