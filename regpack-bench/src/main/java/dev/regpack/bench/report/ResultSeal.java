package dev.regpack.bench.report;

import dev.regpack.bench.eval.SpikeResult;
import dev.regpack.model.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seals a result directory: {@code summary.json} and {@code report.md} are written first and {@code
 * manifest.sha256} is the last write, through a temporary file and an atomic move. A directory that
 * was modified after its manifest was written is detected by {@link #verify}: a changed byte, a
 * deleted file and a file that the manifest does not list are all reported, and so is a symbolic
 * link, which is never followed.
 *
 * <p>The manifest is data from outside when it comes back from CI, so {@link #verify} takes no path
 * from it that is not a plain relative path inside the directory.
 */
public final class ResultSeal {
  /** The manifest file name. */
  public static final String MANIFEST = "manifest.sha256";

  private static final String TEMPORARY = "manifest.sha256.tmp";
  private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  (.+)");
  private static final List<String> KIND_ORDER =
      List.of("BAD_MANIFEST", "MISSING", "HASH_MISMATCH", "UNLISTED", "SYMLINK");

  private ResultSeal() {}

  /**
   * Writes {@code summary.json} and {@code report.md}, then the manifest of every file of the
   * directory (sorted lines {@code <hex> <relative path>}, LF) as the last write.
   *
   * @throws FileAlreadyExistsException when the directory already has a manifest
   * @throws IOException when the directory holds a symbolic link or another special file, a file
   *     name that cannot be written in a manifest line, or on any I/O failure
   */
  public static void seal(Path dir, SpikeResult r, String reportMd) throws IOException {
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchFileException(dir.toString(), null, "not a directory");
    }
    Path manifest = dir.resolve(MANIFEST);
    if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(manifest.toString(), null, "the directory is sealed");
    }
    // before anything is written: a link in the directory could redirect a write
    refuseSpecialFiles(dir);
    Files.writeString(
        dir.resolve("summary.json"),
        r.toJson() + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.writeString(
        dir.resolve("report.md"),
        reportMd,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    // every hash is taken from the files as they are now, after the last file was written
    StringBuilder text = new StringBuilder();
    for (Path file : regularFiles(dir)) {
      String relative = relative(dir, file);
      if (relative.equals(MANIFEST) || relative.equals(TEMPORARY)) {
        continue;
      }
      Optional<String> unsafe = unsafePath(relative);
      if (unsafe.isPresent()) {
        throw new IOException("a file that a manifest cannot list: " + unsafe.get());
      }
      text.append(Sha256.of(Files.readAllBytes(file)).hex())
          .append("  ")
          .append(relative)
          .append('\n');
    }
    Path temporary = dir.resolve(TEMPORARY);
    Files.writeString(
        temporary,
        text.toString(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Checks the directory against its manifest. Returns one entry per problem: {@code MISSING
   * <path>}, {@code HASH_MISMATCH <path>}, {@code UNLISTED <path>}, {@code SYMLINK <path>}, and
   * {@code BAD_MANIFEST <detail>} when the manifest itself is malformed or names an unsafe path.
   * The list is sorted by kind (in the order of this list, {@code BAD_MANIFEST} first), then by
   * path, whatever the walk order of the file system; it is empty when the directory is exactly
   * what was sealed.
   */
  public static List<String> verify(Path dir) {
    List<String> problems = new ArrayList<>();
    Path manifest = dir.resolve(MANIFEST);
    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      problems.add("MISSING " + MANIFEST);
      return ordered(problems);
    }
    if (Files.isSymbolicLink(manifest)
        || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
      problems.add("SYMLINK " + MANIFEST);
      return ordered(problems);
    }
    Map<String, String> listed = new TreeMap<>();
    try {
      String content = Files.readString(manifest, StandardCharsets.UTF_8);
      if (!content.isEmpty() && !content.endsWith("\n")) {
        problems.add("BAD_MANIFEST the last line has no line feed");
      }
      String[] manifestLines = content.split("\n", -1);
      int count =
          content.isEmpty() || content.endsWith("\n")
              ? manifestLines.length - 1
              : manifestLines.length;
      for (int lineNumber = 1; lineNumber <= count; lineNumber++) {
        String line = manifestLines[lineNumber - 1];
        Matcher match = LINE.matcher(line);
        if (!match.matches()) {
          problems.add("BAD_MANIFEST line " + lineNumber + " is not '<64 hex>  <path>'");
          continue;
        }
        String path = match.group(2);
        Optional<String> unsafe = unsafePath(path);
        if (unsafe.isPresent()) {
          problems.add("BAD_MANIFEST line " + lineNumber + ": " + unsafe.get());
          continue;
        }
        if (listed.put(path, match.group(1)) != null) {
          problems.add("BAD_MANIFEST line " + lineNumber + ": " + path + " is listed twice");
        }
      }
    } catch (IOException | RuntimeException e) {
      problems.add("BAD_MANIFEST unreadable: " + e.getClass().getSimpleName());
      return ordered(problems);
    }

    for (Map.Entry<String, String> entry : listed.entrySet()) {
      Path file = dir.resolve(entry.getKey());
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        problems.add("MISSING " + entry.getKey());
      } else if (Files.isSymbolicLink(file)) {
        problems.add("SYMLINK " + entry.getKey());
      } else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        problems.add("MISSING " + entry.getKey());
      } else {
        try {
          if (!Sha256.of(Files.readAllBytes(file)).hex().equals(entry.getValue())) {
            problems.add("HASH_MISMATCH " + entry.getKey());
          }
        } catch (IOException e) {
          problems.add("HASH_MISMATCH " + entry.getKey());
        }
      }
    }

    Set<String> reported = new HashSet<>();
    for (String problem : problems) {
      reported.add(problem.substring(problem.indexOf(' ') + 1));
    }
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
              // a Windows junction is a directory and also "other": report it, never descend
              if (!d.equals(dir) && (attrs.isSymbolicLink() || attrs.isOther())) {
                String relative = relative(dir, d);
                if (reported.add(relative)) {
                  problems.add("SYMLINK " + relative);
                }
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String relative = relative(dir, file);
              if (relative.equals(MANIFEST)) {
                return FileVisitResult.CONTINUE;
              }
              if (attrs.isSymbolicLink() || attrs.isOther()) {
                if (reported.add(relative)) {
                  problems.add("SYMLINK " + relative);
                }
              } else if (!listed.containsKey(relative)) {
                problems.add("UNLISTED " + relative);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      problems.add("BAD_MANIFEST the directory cannot be read: " + e.getClass().getSimpleName());
    }
    return ordered(problems);
  }

  /**
   * The problems by kind, then by path or detail: the walk order of the file system is not kept.
   */
  private static List<String> ordered(List<String> problems) {
    List<String> sorted = new ArrayList<>(problems);
    sorted.sort(
        Comparator.comparingInt((String p) -> KIND_ORDER.indexOf(p.substring(0, p.indexOf(' '))))
            .thenComparing(Comparator.naturalOrder()));
    return sorted;
  }

  private static Optional<String> unsafePath(String path) {
    if (path.equals(MANIFEST)) {
      return Optional.of("the manifest lists itself");
    }
    if (path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
      return Optional.of("not a relative path: " + path);
    }
    for (int i = 0; i < path.length(); i++) {
      if (path.charAt(i) < 0x20) {
        return Optional.of("a control character in a path");
      }
    }
    for (String part : path.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        return Optional.of("not a normal relative path: " + path);
      }
    }
    return Optional.empty();
  }

  private static String relative(Path dir, Path file) {
    StringBuilder out = new StringBuilder();
    for (Path part : dir.relativize(file)) {
      if (out.length() > 0) {
        out.append('/');
      }
      out.append(part.toString());
    }
    return out.toString();
  }

  /** Every regular file under the directory, sorted by relative path. */
  private static List<Path> regularFiles(Path dir) throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              files.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(files, (a, b) -> relative(dir, a).compareTo(relative(dir, b)));
    return files;
  }

  private static void refuseSpecialFiles(Path dir) throws IOException {
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink() || attrs.isOther()) {
              throw new IOException(
                  "a symbolic link or special file cannot be sealed: " + relative(dir, file));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink() || attrs.isOther()) {
              throw new IOException("a link or junction cannot be sealed: " + relative(dir, d));
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
