package dev.regpack.pack.source;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.pack.PackFormat;
import dev.regpack.pack.check.PackProblem;
import dev.regpack.pack.yaml.PackSyntaxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The file tree of a pack: relative paths with {@code /} separators mapped to their bytes. A source
 * is immutable, holds copies and refuses a tree that violates the file rules (plan task T6) with a
 * {@link PackSyntaxException} whose code is one of {@code PATH_INVALID}, {@code PATH_EXTENSION},
 * {@code PATH_RESERVED_NAME}, {@code PATH_CASE_COLLISION}, {@code PATH_FILE_AND_DIRECTORY}, {@code
 * PACK_TOO_MANY_FILES}, {@code PACK_FILE_TOO_LARGE}, {@code PACK_TOO_LARGE} or {@code
 * PACK_NOT_REGULAR} (the line of the exception is 0 and its excerpt names the path); {@link
 * #text(String)} adds {@code PACK_BOM} and {@code PACK_BAD_UTF8}.
 *
 * <p>File rules: segments {@code [A-Za-z0-9_.-]+} that neither start nor end with a dot, no empty
 * segment, no backslash, at most 256 characters and {@value #MAX_DIRECTORY_DEPTH} directory levels
 * (on every entry point: {@link #of}, {@link #fromDirectory} and {@link #fromClasspath}); extension
 * {@code .yaml} or {@code .json}, or exactly {@code CHECKSUMS} or {@code ATTESTATION} at the root;
 * at most {@link PackFormat#MAX_FILES} files, {@link PackFormat#MAX_FILE_BYTES} each and {@link
 * PackFormat#MAX_TOTAL_BYTES} together; no two paths equal ignoring case; no segment whose base
 * name (before the first dot) is a Windows device name.
 */
public final class PackSource {
  /** File name of the integrity file. */
  public static final String CHECKSUMS = "CHECKSUMS";

  /** File name of the (opaque, unverified in P2a) attestation. */
  public static final String ATTESTATION = "ATTESTATION";

  private static final int MAX_PATH_CHARS = 256;
  static final int MAX_DIRECTORY_DEPTH = 16;
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
  private static final Set<String> DEVICE_NAMES = deviceNames();

  private final SortedMap<String, byte[]> files;

  private PackSource(SortedMap<String, byte[]> files) {
    this.files = files;
  }

  private static Set<String> deviceNames() {
    Set<String> names = new HashSet<>(Set.of("CON", "PRN", "AUX", "NUL"));
    for (int i = 1; i <= 9; i++) {
      names.add("COM" + i);
      names.add("LPT" + i);
    }
    return Collections.unmodifiableSet(names);
  }

  static PackSyntaxException refuse(String code, String subject) {
    return new PackSyntaxException(code, 0, subject);
  }

  // ---------------------------------------------------------------------------------------------
  // construction

  /** Validates the names, the counts and the sizes, and copies the bytes. */
  public static PackSource of(Map<String, byte[]> files) {
    Objects.requireNonNull(files, "files");
    SortedMap<String, byte[]> sorted = new TreeMap<>();
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      sorted.put(
          Objects.requireNonNull(entry.getKey(), "path"),
          Objects.requireNonNull(entry.getValue(), "bytes"));
    }
    if (sorted.size() > PackFormat.MAX_FILES) {
      throw refuse("PACK_TOO_MANY_FILES", Integer.toString(sorted.size()));
    }
    // validate in path order so that the first error does not depend on the map's iteration order
    for (String path : sorted.keySet()) {
      checkPath(path);
    }
    Map<String, String> lowerToPath = new HashMap<>();
    for (String path : sorted.keySet()) {
      String previous = lowerToPath.put(path.toLowerCase(Locale.ROOT), path);
      if (previous != null) {
        throw refuse("PATH_CASE_COLLISION", path);
      }
    }
    for (String path : sorted.keySet()) {
      for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
        String directory = path.substring(0, slash);
        String clash = lowerToPath.get(directory.toLowerCase(Locale.ROOT));
        if (clash != null) {
          throw refuse(
              clash.equals(directory) ? "PATH_FILE_AND_DIRECTORY" : "PATH_CASE_COLLISION", path);
        }
      }
    }
    long total = 0;
    for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
      int size = entry.getValue().length;
      if (size > PackFormat.MAX_FILE_BYTES) {
        throw refuse("PACK_FILE_TOO_LARGE", entry.getKey());
      }
      total += size;
      if (total > PackFormat.MAX_TOTAL_BYTES) {
        throw refuse("PACK_TOO_LARGE", entry.getKey());
      }
    }
    SortedMap<String, byte[]> copy = new TreeMap<>();
    sorted.forEach((path, bytes) -> copy.put(path, bytes.clone()));
    return new PackSource(Collections.unmodifiableSortedMap(copy));
  }

  /** One reason to refuse a directory entry; the smallest by path, then code, is reported. */
  private record Violation(String path, String code) implements Comparable<Violation> {
    @Override
    public int compareTo(Violation other) {
      int byPath = path.compareTo(other.path);
      return byPath != 0 ? byPath : code.compareTo(other.code);
    }
  }

  /**
   * Reads a directory: regular files only (a symbolic link or any other reparse point, the root
   * included, is {@code PACK_NOT_REGULAR}), counts and sizes checked from the file attributes
   * before any file is read, at most {@link PackFormat#MAX_FILE_BYTES}+1 bytes read per file. The
   * walk collects every violation of an entry before it throws, so the error reported is the one of
   * the smallest path (then code) whatever order the file system lists the entries in; the count
   * limits come first, then the entries, then the total size in path order.
   */
  public static PackSource fromDirectory(Path dir) {
    Objects.requireNonNull(dir, "dir");
    Map<String, Path> found = new TreeMap<>();
    Map<String, Long> sizes = new HashMap<>();
    List<Violation> violations = new ArrayList<>();
    int[] fileCount = {0};
    int[] directoryCount = {0};
    boolean[] tooMany = {false};
    try {
      Files.walkFileTree(
          dir,
          EnumSet.noneOf(FileVisitOption.class),
          Integer.MAX_VALUE,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
              if (d.equals(dir)) {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                  // a Windows junction is a directory that is also "other"
                  throw refuse("PACK_NOT_REGULAR", String.valueOf(dir.getFileName()));
                }
                return FileVisitResult.CONTINUE;
              }
              String rel = relative(dir, d);
              if (attrs.isSymbolicLink() || attrs.isOther()) {
                // do not descend into it
                violations.add(new Violation(rel, "PACK_NOT_REGULAR"));
                return FileVisitResult.SKIP_SUBTREE;
              }
              if (++directoryCount[0] > PackFormat.MAX_FILES) {
                tooMany[0] = true;
                return FileVisitResult.TERMINATE;
              }
              if (dir.relativize(d).getNameCount() > MAX_DIRECTORY_DEPTH) {
                violations.add(new Violation(rel, "PATH_INVALID"));
                return FileVisitResult.SKIP_SUBTREE;
              }
              try {
                checkDirectoryName(rel);
              } catch (PackSyntaxException e) {
                violations.add(new Violation(rel, e.code()));
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes unused)
                throws IOException {
              if (file.equals(dir)) {
                throw refuse("PACK_NOT_REGULAR", file.getFileName().toString());
              }
              String rel = relative(dir, file);
              if (++fileCount[0] > PackFormat.MAX_FILES) {
                tooMany[0] = true;
                return FileVisitResult.TERMINATE;
              }
              BasicFileAttributes attrs =
                  Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
              if (!attrs.isRegularFile()) {
                violations.add(new Violation(rel, "PACK_NOT_REGULAR"));
                return FileVisitResult.CONTINUE;
              }
              try {
                checkPath(rel);
              } catch (PackSyntaxException e) {
                violations.add(new Violation(rel, e.code()));
                return FileVisitResult.CONTINUE;
              }
              if (attrs.size() > PackFormat.MAX_FILE_BYTES) {
                violations.add(new Violation(rel, "PACK_FILE_TOO_LARGE"));
                return FileVisitResult.CONTINUE;
              }
              found.put(rel, file);
              sizes.put(rel, attrs.size());
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
              throw exc;
            }
          });
      if (tooMany[0]) {
        throw refuse("PACK_TOO_MANY_FILES", "over " + PackFormat.MAX_FILES);
      }
      if (!violations.isEmpty()) {
        Violation first = Collections.min(violations);
        throw refuse(first.code(), first.path());
      }
      long total = 0;
      for (String rel : found.keySet()) {
        total += sizes.get(rel);
        if (total > PackFormat.MAX_TOTAL_BYTES) {
          throw refuse("PACK_TOO_LARGE", rel);
        }
      }
      Map<String, byte[]> files = new TreeMap<>();
      for (Map.Entry<String, Path> entry : found.entrySet()) {
        try (InputStream in = Files.newInputStream(entry.getValue(), LinkOption.NOFOLLOW_LINKS)) {
          files.put(entry.getKey(), readBounded(in, entry.getKey()));
        }
      }
      return of(files);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads a pack bundled as class path resources below {@code root} (for example {@code
   * packs/sample.s0}): {@code root/CHECKSUMS} first, then exactly the files its well-formed lines
   * list, each name validated before any resource is requested. A listed file that is absent is not
   * in the source ({@link Checksums#verify(PackSource)} reports it).
   */
  public static PackSource fromClasspath(Class<?> anchor, String root) {
    Objects.requireNonNull(anchor, "anchor");
    Objects.requireNonNull(root, "root");
    checkDirectoryName(root);
    String base = "/" + root + "/";
    Map<String, byte[]> files = new TreeMap<>();
    byte[] checksums = readResource(anchor, base + CHECKSUMS, CHECKSUMS);
    if (checksums == null) {
      throw refuse("CHECKSUMS_MISSING", root);
    }
    files.put(CHECKSUMS, checksums);
    List<String> listed = Checksums.listedPaths(new String(checksums, UTF_8).replace("\r\n", "\n"));
    if (listed.size() > PackFormat.MAX_FILES) {
      throw refuse("PACK_TOO_MANY_FILES", root);
    }
    for (String path : listed) {
      checkPath(path); // every name before the first request
    }
    long total = checksums.length;
    for (String path : listed) {
      byte[] data = readResource(anchor, base + path, path);
      if (data != null) {
        total += data.length;
        if (total > PackFormat.MAX_TOTAL_BYTES) {
          throw refuse("PACK_TOO_LARGE", path);
        }
        files.put(path, data);
      }
    }
    return of(files);
  }

  private static byte[] readResource(Class<?> anchor, String name, String shown) {
    try (InputStream in = anchor.getResourceAsStream(name)) {
      return in == null ? null : readBounded(in, shown);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] readBounded(InputStream in, String shown) throws IOException {
    byte[] data = in.readNBytes(PackFormat.MAX_FILE_BYTES + 1);
    if (data.length > PackFormat.MAX_FILE_BYTES) {
      throw refuse("PACK_FILE_TOO_LARGE", shown);
    }
    return data;
  }

  private static String relative(Path root, Path file) {
    Path rel = root.relativize(file);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < rel.getNameCount(); i++) {
      if (i > 0) {
        out.append('/');
      }
      out.append(rel.getName(i));
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // names

  /** Whether a string is a valid pack file path (segments, length, extension, reserved names). */
  public static boolean isValidPath(String path) {
    try {
      checkPath(path);
      return true;
    } catch (PackSyntaxException e) {
      return false;
    }
  }

  /** Validates a whole file path: segments, length, extension, reserved names. */
  static void checkPath(String path) {
    checkSegments(path);
    if (path.chars().filter(c -> c == '/').count() > MAX_DIRECTORY_DEPTH) {
      throw refuse("PATH_INVALID", path); // more directory levels than a pack may have
    }
    int slash = path.lastIndexOf('/');
    String name = path.substring(slash + 1);
    boolean root = slash < 0;
    boolean special = root && (name.equals(CHECKSUMS) || name.equals(ATTESTATION));
    if (!special && !name.endsWith(".yaml") && !name.endsWith(".json")) {
      throw refuse("PATH_EXTENSION", path);
    }
  }

  /** Validates a directory name (or a class path root): segments and reserved names only. */
  private static void checkDirectoryName(String path) {
    checkSegments(path);
  }

  private static void checkSegments(String path) {
    if (path.isEmpty() || path.length() > MAX_PATH_CHARS) {
      throw refuse("PATH_INVALID", path);
    }
    int start = 0;
    while (start <= path.length()) {
      int end = path.indexOf('/', start);
      if (end < 0) {
        end = path.length();
      }
      String segment = path.substring(start, end);
      if (segment.isEmpty()
          || segment.charAt(0) == '.'
          || segment.charAt(segment.length() - 1) == '.'
          || !SEGMENT.matcher(segment).matches()) {
        throw refuse("PATH_INVALID", path);
      }
      int dot = segment.indexOf('.');
      String base = dot < 0 ? segment : segment.substring(0, dot);
      if (DEVICE_NAMES.contains(base.toUpperCase(Locale.ROOT))) {
        throw refuse("PATH_RESERVED_NAME", path);
      }
      start = end + 1;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // access

  /** The paths, ascending; unmodifiable. */
  public Set<String> paths() {
    return Collections.unmodifiableSet(files.keySet());
  }

  public boolean has(String path) {
    return files.containsKey(path);
  }

  /** A copy of the bytes of a file. */
  public byte[] bytes(String path) {
    byte[] data = files.get(path);
    if (data == null) {
      throw new IllegalArgumentException(
          "no such pack file: " + PackProblem.excerpt(String.valueOf(path)));
    }
    return data.clone();
  }

  /**
   * The text of a file: strict UTF-8, no byte order mark, the line ends untouched. CRLF is read as
   * LF exactly once, by the consumer: {@link dev.regpack.pack.yaml.StrictYaml#parse} (which also
   * refuses any other CR), {@link Checksums#verify} for CHECKSUMS; JSON reads CR as white space.
   */
  public String text(String path) {
    byte[] data = files.get(path);
    if (data == null) {
      throw new IllegalArgumentException(
          "no such pack file: " + PackProblem.excerpt(String.valueOf(path)));
    }
    if (data.length >= 3
        && data[0] == (byte) 0xEF
        && data[1] == (byte) 0xBB
        && data[2] == (byte) 0xBF) {
      throw refuse("PACK_BOM", path);
    }
    CharsetDecoder decoder =
        UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    String text;
    try {
      text = decoder.decode(ByteBuffer.wrap(data)).toString();
    } catch (CharacterCodingException e) {
      throw refuse("PACK_BAD_UTF8", path);
    }
    return text;
  }
}
