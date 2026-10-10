package dev.regpack.model.json;

/** Thrown when JSON text is not acceptable to the strict parser. */
public final class JsonException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public JsonException(String message) {
    super(message);
  }
}
