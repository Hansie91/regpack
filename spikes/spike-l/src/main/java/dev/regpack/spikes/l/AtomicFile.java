package dev.regpack.spikes.l;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a file so that a reader sees the old content or the new one, never half of it. */
final class AtomicFile {
  private AtomicFile() {}

  static void write(Path file, String text) throws IOException {
    Path directory = file.toAbsolutePath().getParent();
    Path temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, text, StandardCharsets.UTF_8);
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
