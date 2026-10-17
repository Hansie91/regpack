package dev.regpack.pack.yaml;

/**
 * A pack file is not PackYaml/1. Carries an upper-case ASCII code (for example {@code
 * YAML_DUPLICATE_KEY}), the 1-based line and a short excerpt of the offending text.
 */
public final class PackSyntaxException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final int line;

  public PackSyntaxException(String code, int line, String excerpt) {
    super("line " + line + ": " + code + ": " + excerpt);
    this.code = code;
    this.line = line;
  }

  public String code() {
    return code;
  }

  public int line() {
    return line;
  }
}
