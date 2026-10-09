package dev.regpack.tools;

import java.io.PrintStream;

/** Entry point of the build and CI gates. */
public final class Gates {
  private Gates() {}

  /** Runs the gate named by the first argument and exits with its status. */
  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /**
   * Runs a gate.
   *
   * @return 0 on success, 1 when violations were found, 2 on a usage error
   */
  static int run(String[] args, PrintStream out, PrintStream err) {
    err.println("usage: Gates <traceability|pins|eol>");
    return 2;
  }
}
