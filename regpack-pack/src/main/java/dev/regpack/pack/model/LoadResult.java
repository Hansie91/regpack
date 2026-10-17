package dev.regpack.pack.model;

import dev.regpack.pack.check.PackProblem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of {@link PackLoader#load}: fail closed, so {@code pack} is present exactly when
 * {@code problems} is empty. The problems are sorted (path, line, code, message) and without
 * duplicates.
 */
public record LoadResult(Optional<LoadedPack> pack, List<PackProblem> problems) {
  public LoadResult {
    Objects.requireNonNull(pack, "pack");
    problems = List.copyOf(problems);
    if (pack.isPresent() != problems.isEmpty()) {
      throw new IllegalArgumentException("a pack is returned exactly when there are no problems");
    }
  }
}
