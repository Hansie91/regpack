package dev.regpack.bench.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The arguments of one command: options that take a value ({@code --dir x}, repeatable), flags
 * ({@code --dry-run}) and positional words. An unknown option, a missing value and a repeated
 * single-valued option are usage errors ({@link IllegalArgumentException}).
 */
final class Args {
  private final Map<String, List<String>> values = new LinkedHashMap<>();
  private final Set<String> flags = new LinkedHashSet<>();
  private final List<String> positionals = new ArrayList<>();

  private Args() {}

  /**
   * Parses {@code args} from index {@code from}.
   *
   * @param valued the options that take a value
   * @param flagNames the options that take none
   */
  static Args parse(String[] args, int from, Set<String> valued, Set<String> flagNames) {
    Args parsed = new Args();
    for (int i = from; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--")) {
        if (flagNames.contains(arg)) {
          parsed.flags.add(arg);
        } else if (valued.contains(arg)) {
          if (i + 1 >= args.length) {
            throw new IllegalArgumentException(arg + " needs a value");
          }
          parsed.values.computeIfAbsent(arg, k -> new ArrayList<>()).add(args[++i]);
        } else {
          throw new IllegalArgumentException("unknown option " + arg);
        }
      } else {
        parsed.positionals.add(arg);
      }
    }
    return parsed;
  }

  /** The value of an option given at most once. */
  Optional<String> single(String name) {
    List<String> list = values.getOrDefault(name, List.of());
    if (list.size() > 1) {
      throw new IllegalArgumentException(name + " is given more than once");
    }
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** The value of an option that must be present once. */
  String required(String name) {
    return single(name).orElseThrow(() -> new IllegalArgumentException(name + " is required"));
  }

  /** Every value of a repeatable option. */
  List<String> all(String name) {
    return values.getOrDefault(name, List.of());
  }

  boolean flag(String name) {
    return flags.contains(name);
  }

  List<String> positionals() {
    return positionals;
  }

  /**
   * A command that takes no positional words: a stray word is a usage error, so that a typo such as
   * {@code import --spike SP-R somedir} is not silently read as something else.
   */
  Args noPositionals() {
    if (!positionals.isEmpty()) {
      throw new IllegalArgumentException("unexpected argument " + positionals.get(0));
    }
    return this;
  }
}
