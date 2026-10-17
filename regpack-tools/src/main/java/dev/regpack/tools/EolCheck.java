package dev.regpack.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Line-ending, byte-order-mark and ASCII rules per file type, plus the hidden-character rule for
 * tracked text sources (bidirectional controls, zero-width characters, a byte order mark anywhere,
 * Unicode line separators). Unknown file types are ignored. {@link #check} reads working-tree
 * files, never index blobs.
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

  /**
   * Tracked text sources that must hold no invisible, direction-changing or line-separating
   * character (the Trojan-Source family): such a character can make code read differently from how
   * it runs.
   */
  private static final Set<String> HIDDEN_CHARACTER_EXTENSIONS =
      Set.of(
          ".java",
          ".yaml",
          ".yml",
          ".md",
          ".tsv",
          ".csv",
          ".sh",
          ".ps1",
          ".json",
          ".xml",
          ".properties");

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
    List<String> problems = new ArrayList<>();
    if (!crlf && !lf) {
      addHiddenCharacterProblems(path, extension, content, false, problems);
      return List.copyOf(problems);
    }
    boolean bom =
        content.length >= 3
            && content[0] == (byte) 0xEF
            && content[1] == (byte) 0xBB
            && content[2] == (byte) 0xBF;
    if (bom) {
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
    addHiddenCharacterProblems(path, extension, content, bom, problems);
    return List.copyOf(problems);
  }

  /**
   * The hidden-character rule: one problem per kind and file, at the first line of its first hit. A
   * leading byte order mark of a file type with the BOM rule is already reported as {@code BOM}.
   */
  private static void addHiddenCharacterProblems(
      String path, String extension, byte[] content, boolean bomReported, List<String> problems) {
    if (!HIDDEN_CHARACTER_EXTENSIONS.contains(extension)) {
      return;
    }
    int bidi = 0;
    int zeroWidth = 0;
    int separator = 0;
    int line = 1;
    for (int i = 0; i < content.length; i++) {
      int b = content[i] & 0xFF;
      if (b == 0x0A) {
        line++;
      } else if (b == 0xE2 && i + 2 < content.length) {
        int b1 = content[i + 1] & 0xFF;
        int b2 = content[i + 2] & 0xFF;
        if (b1 == 0x80 && b2 >= 0xAA && b2 <= 0xAE) {
          bidi = bidi == 0 ? line : bidi; // U+202A to U+202E
        } else if (b1 == 0x81 && b2 >= 0xA6 && b2 <= 0xA9) {
          bidi = bidi == 0 ? line : bidi; // U+2066 to U+2069
        } else if (b1 == 0x80 && b2 >= 0x8B && b2 <= 0x8F) {
          zeroWidth = zeroWidth == 0 ? line : zeroWidth; // U+200B to U+200F
        } else if (b1 == 0x81 && b2 == 0xA0) {
          zeroWidth = zeroWidth == 0 ? line : zeroWidth; // U+2060
        } else if (b1 == 0x80 && (b2 == 0xA8 || b2 == 0xA9)) {
          separator = separator == 0 ? line : separator; // U+2028, U+2029
        }
      } else if (b == 0xEF
          && i + 2 < content.length
          && (content[i + 1] & 0xFF) == 0xBB
          && (content[i + 2] & 0xFF) == 0xBF) {
        if (!(i == 0 && bomReported)) {
          zeroWidth = zeroWidth == 0 ? line : zeroWidth; // U+FEFF
        }
      } else if (b == 0xC2 && i + 1 < content.length && (content[i + 1] & 0xFF) == 0x85) {
        separator = separator == 0 ? line : separator; // U+0085
      }
    }
    if (bidi != 0) {
      problems.add(path + ": BIDI_CONTROL (line " + bidi + ")");
    }
    if (zeroWidth != 0) {
      problems.add(path + ": ZERO_WIDTH (line " + zeroWidth + ")");
    }
    if (separator != 0) {
      problems.add(path + ": UNICODE_LINE_SEPARATOR (line " + separator + ")");
    }
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
