package dev.regpack.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Line-ending, byte-order-mark and ASCII rules per file type, plus the hidden-character rule for
 * every tracked file except a binary deny-list: the file must be valid UTF-8 and hold none of the
 * code points that {@link HiddenCharacters} refuses (the character policy of the Regime Pack:
 * controls, format characters, bidirectional controls, zero-width characters, a byte order mark
 * anywhere, Unicode line separators, private use, noncharacters, default-ignorables, tag
 * characters). {@link #check} reads working-tree files, never index blobs.
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
   * Binary file types: not text, so neither decoded nor scanned. Every other tracked file is
   * decoded as strict UTF-8 and scanned for hidden characters; a file that is not valid UTF-8 and
   * not of one of these types is a problem, never a silent skip.
   */
  private static final Set<String> BINARY_EXTENSIONS =
      Set.of(
          ".jar", ".zip", ".gz", ".tgz", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".class",
          ".bin");

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
    if (BINARY_EXTENSIONS.contains(extension)) {
      return List.of();
    }
    boolean crlf = CRLF_EXTENSIONS.contains(extension);
    boolean lf = LF_EXTENSIONS.contains(extension) || LF_NAMES.contains(name);
    List<String> problems = new ArrayList<>();
    if (!crlf && !lf) {
      addHiddenCharacterProblems(path, content, false, problems);
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
    addHiddenCharacterProblems(path, content, bom, problems);
    return List.copyOf(problems);
  }

  /**
   * The hidden-character rule: the file is decoded as strict UTF-8 (anything else is {@code
   * NOT_UTF8}) and every code point of the {@link HiddenCharacters} policy is a problem. One
   * problem per kind and file, at the first line of its first hit. A leading byte order mark of a
   * file type with the BOM rule is already reported as {@code BOM}.
   */
  private static void addHiddenCharacterProblems(
      String path, byte[] content, boolean bomReported, List<String> problems) {
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(content))
              .toString();
    } catch (CharacterCodingException e) {
      problems.add(path + ": NOT_UTF8");
      return;
    }
    Map<HiddenCharacters.Kind, String> first = new EnumMap<>(HiddenCharacters.Kind.class);
    int line = 1;
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '\n') {
        line++;
      } else if (HiddenCharacters.isHidden(cp) && !(cp == 0xFEFF && i == 1 && bomReported)) {
        HiddenCharacters.Kind kind = HiddenCharacters.kindOf(cp);
        String where =
            kind == HiddenCharacters.Kind.HIDDEN_CHARACTER
                ? "(line " + line + ", " + String.format(Locale.ROOT, "U+%04X", cp) + ")"
                : "(line " + line + ")";
        first.putIfAbsent(kind, where);
      }
    }
    first.forEach((kind, where) -> problems.add(path + ": " + kind.name() + " " + where));
  }

  /**
   * Checks files of the working tree.
   *
   * @param root the repository root
   * @param relativePaths the files to check; a directory (a submodule) is skipped, a path that does
   *     not exist or is not a regular file is reported as {@code NOT_FOUND}
   * @return the problems, empty when clean
   */
  public static List<String> check(Path root, List<String> relativePaths) {
    List<String> problems = new ArrayList<>();
    for (String relative : relativePaths) {
      Path file = root.resolve(relative);
      if (Files.isDirectory(file)) {
        continue;
      }
      if (!Files.isRegularFile(file)) {
        problems.add(relative + ": NOT_FOUND");
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
