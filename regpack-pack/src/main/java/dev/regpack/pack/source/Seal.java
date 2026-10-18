package dev.regpack.pack.source;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.regpack.pack.yaml.PackSyntaxException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rewrites the CHECKSUMS file of a pack directory from its files (a pack author's tool). The result
 * is canonical, so sealing twice gives the same bytes.
 */
public final class Seal {
  private Seal() {}

  /**
   * Seals {@code dir}: reads it as a {@link PackSource} (so every file rule applies), writes {@code
   * dir/CHECKSUMS} (UTF-8, LF) and returns the number of files it lists.
   */
  public static int seal(Path dir) {
    PackSource source = PackSource.fromDirectory(dir);
    String text = Checksums.render(source);
    try {
      Files.write(dir.resolve(PackSource.CHECKSUMS), text.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return text.isEmpty() ? 0 : text.split("\n", -1).length - 1;
  }

  /** The command: {@code Seal <pack directory>}; prints {@code sealed <n> files}. */
  public static void main(String[] args) {
    int status = run(args, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length != 1) {
      err.print("usage: Seal <pack directory>\n");
      return 2;
    }
    try {
      int n = seal(Path.of(args[0]));
      out.print("sealed " + n + " files\n");
      return 0;
    } catch (PackSyntaxException e) {
      err.print("seal failed: " + e.getMessage() + "\n");
      return 1;
    } catch (UncheckedIOException e) {
      err.print("seal failed: " + e.getCause().getClass().getSimpleName() + "\n");
      return 1;
    }
  }
}
